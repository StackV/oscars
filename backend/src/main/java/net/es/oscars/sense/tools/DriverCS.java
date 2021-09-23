/*
 * SENSE Resource Manager (SENSE-RM) Copyright (c) 2016, The Regents
 * of the University of California, through Lawrence Berkeley National
 * Laboratory (subject to receipt of any required approvals from the
 * U.S. Dept. of Energy).  All rights reserved.
 *
 * If you have questions about your rights to use or distribute this
 * software, please contact Berkeley Lab's Innovation & Partnerships
 * Office at IPO@lbl.gov.
 *
 * NOTICE.  This Software was developed under funding from the
 * U.S. Department of Energy and the U.S. Government consequently retains
 * certain rights. As such, the U.S. Government has been granted for
 * itself and others acting on its behalf a paid-up, nonexclusive,
 * irrevocable, worldwide license in the Software to reproduce,
 * distribute copies to the public, prepare derivative works, and perform
 * publicly and display publicly, and to permit other to do so.
 *
 */
package net.es.oscars.sense.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.XMLGregorianCalendar;

import org.apache.jena.ext.com.google.common.base.Strings;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import net.es.nsi.lib.soap.gen.nsi_2_0.connection.ifce.ServiceException;
import net.es.nsi.lib.soap.gen.nsi_2_0.connection.types.LifecycleStateEnumType;
import net.es.nsi.lib.soap.gen.nsi_2_0.connection.types.ReservationRequestCriteriaType;
import net.es.nsi.lib.soap.gen.nsi_2_0.connection.types.ReserveType;
import net.es.nsi.lib.soap.gen.nsi_2_0.connection.types.ScheduleType;
import net.es.nsi.lib.soap.gen.nsi_2_0.services.point2point.ObjectFactory;
import net.es.nsi.lib.soap.gen.nsi_2_0.services.point2point.P2PServiceBaseType;
import net.es.nsi.lib.soap.gen.nsi_2_0.services.types.DirectionalityType;
import net.es.oscars.resv.db.ConnectionRepository;
import net.es.oscars.resv.ent.Connection;
import net.es.oscars.resv.enums.Phase;
import net.es.oscars.sense.db.SENSEDeltaConnectionRepository;
import net.es.oscars.sense.definitions.Mrs;
import net.es.oscars.sense.definitions.Nml;
import net.es.oscars.sense.definitions.NmlExistsDuring;
import net.es.oscars.sense.definitions.Nsi;
import net.es.oscars.sense.definitions.Sd;
import net.es.oscars.sense.definitions.SimpleLabel;
import net.es.oscars.sense.definitions.SimpleStp;
import net.es.oscars.sense.definitions.db.ConnectionMap;
import net.es.oscars.sense.definitions.db.ConnectionMapRepository;
import net.es.oscars.sense.definitions.db.StpMapping;
import net.es.oscars.sense.definitions.mrml.MrsBandwidthService;
import net.es.oscars.sense.definitions.mrml.MrsBandwidthType;
import net.es.oscars.sense.definitions.mrml.MrsUnits;
import net.es.oscars.sense.definitions.mrml.NmlLabel;
import net.es.oscars.sense.definitions.mrml.StpHolder;
import net.es.oscars.sense.model.DeltaConnection;
import net.es.oscars.sense.model.entities.SENSEModel;
import net.es.oscars.web.beans.CurrentlyHeldEntry;

/**
 * A provider implementing MRML delta operations using the NSI Connection
 * Service.
 *
 * @author hacksaw
 */
@Slf4j
@Component
public class DriverCS {

    @Autowired
    private SENSEDeltaConnectionRepository deltaMap;

    @Autowired
    private ConnectionMapRepository cmRepo;

    @Autowired
    private ConnectionRepository connRepo;

    @Value("${nsi.provider-nsa}")
    private String providerNSA;
    @Value("${nsi.allowed-requesters}")
    private String allowedRequesters;
    @Value("${nml.topo-id}")
    private String topoID;

    final ObjectFactory P2PS_FACTORY = new ObjectFactory();
    final net.es.nsi.lib.soap.gen.nsi_2_0.connection.types.ObjectFactory CS_FACTORY = new net.es.nsi.lib.soap.gen.nsi_2_0.connection.types.ObjectFactory();

    // @Autowired
    // private OperationMapRepository operationMap;

    /**
     * Process delta reduction and addition requests up to the pre-commit state,
     * storing connection information for commit processing. We are forgiving of
     * reduction elements not being present, but strict about the creation for
     * addition elements.
     *
     * @param model
     * @param deltaId
     * @param reduction
     * @param addition
     * @throws Exception A
     */
    public void processDelta(SENSEModel model, String deltaId, Optional<Model> reduction, Optional<Model> addition)
            throws Exception {
        log.debug("[processDelta] start deltaId = {}", deltaId);

        DeltaConnection connectionIds = new DeltaConnection();
        connectionIds.setDeltaId(deltaId);

        // We process the reduction first.
        if (reduction.isPresent() && !reduction.get().isEmpty()) {
            log.debug("[processDelta] processing reduction, deltaId = {}", deltaId);
            connectionIds.getTerminates().addAll(processDeltaReduction(reduction.get()));
        }

        // Now the addition.
        if (addition.isPresent() && !addition.get().isEmpty()) {
            log.debug("[processDelta] processing addition, deltaId = {}", deltaId);
            connectionIds.getCommits().addAll(processDeltaAddition(model, deltaId, addition.get()));
        }

        // Store the list of connection ids we will need to handle during commit phase.
        deltaMap.store(connectionIds);
        log.debug("[processDelta] end deltaId = {}", deltaId);
    }

    /**
     * Determine the set of NSI connections that must be removed as part of this
     * delta, leaving the termination operation for the commit.
     *
     * @param m
     * @param deltaId
     * @param reduction
     * @return
     */
    private Set<String> processDeltaReduction(Model reduction) {
        log.debug("[processDeltaReduction] start");

        // The list of connection ids we will need terminate in the delta commit.
        Set<String> terminates = new HashSet<>();

        // We model connections as mrs:SwitchingSubnet objects so query the
        // reduction model for all those provided. We will just delete these.
        ResultSet ssSet = ModelUtil.getResourcesOfType(reduction, Mrs.SwitchingSubnet);
        while (ssSet.hasNext()) {
            QuerySolution querySolution = ssSet.next();

            // Get the SwitchingSubnet resource.
            Resource switchingSubnet = querySolution.get("resource").asResource();

            // The SwitchingSubnet identifier is the global reservation identifier in
            // associated NSI connections. For now we only support the removal of
            // a complete SwitchingSubnet, and not individual ports/vlans.
            String ssid = switchingSubnet.getURI();

            log.debug("[processDeltaReduction] SwitchingSubnet: " + ssid);

            // OSCARS REFACTOR
            // Original:
            /*
             * Look up all the reservation segments associated with this SwitchingSubnet. //
             * TODO: We can ignore any in the TERMINATED but due to an OpenNSA bug we let //
             * them try a reduction on a reservation in the TERMINATING state.
             */
            // List<Reservation> reservations =
            // reservationService.getByGlobalReservationId(ssid).stream()
            // .filter(r -> (LifecycleStateEnumType.TERMINATED != r.getLifecycleState()
            // /* && LifecycleStateEnumType.TERMINATING != r.getLifecycleState()
            // */)).collect(Collectors.toList());
            // //

            // // Terminate using the parent connectionId if one exists.
            // for (Reservation reservation : reservations) {
            // if (Strings.isNullOrEmpty(reservation.getParentConnectionId())) {
            // terminates.add(reservation.getConnectionId());
            // } else {
            // terminates.add(reservation.getParentConnectionId());
            // }
            // }

            // terminates.addAll(reservationService.getByGlobalReservationId(ssid).stream()
            // .filter(r -> (LifecycleStateEnumType.TERMINATED != r.getLifecycleState()
            // && LifecycleStateEnumType.TERMINATING != r.getLifecycleState()))
            // .map(Reservation::getConnectionId).collect(Collectors.toList()));

            // New:
            /**
             * Look up all held entities in the connRepo
             */
            List<Connection> resvs = new ArrayList<>();
            List<Connection> connections = connRepo.findByPhase(Phase.HELD);
            for (Connection c : connections) {
                log.info("[processDeltaReduction] Held connection " + c.getConnectionId());

            }

            //
            log.debug("[processDeltaReduction] done");
        }

        return terminates;
    }

    /**
     * processDeltaAddition
     *
     * @param m
     * @param deltaId
     * @param addition
     * @return
     * @throws DatatypeConfigurationException
     * @throws ServiceException
     * @throws IllegalArgumentException
     * @throws TimeoutException
     */
    private Set<String> processDeltaAddition(SENSEModel m, String deltaId, Model addition)
            throws DatatypeConfigurationException, ServiceException, IllegalArgumentException, TimeoutException,
            Exception {
        log.debug("[processDeltaAddition] start deletaId = {}, model = {}", deltaId, m.toString());

        // This is a list of cid associated with reservations created
        // as part of the delta addition.
        Set<String> commits = new HashSet<>();

        // CorrelationId from NSI these reservation requests go in here.
        List<String> correlationIds = new ArrayList<>();

        // Get the associated model.
        Model model = ModelUtil.unmarshalModel(m.getModel());

        log.debug("[processDeltaAddition] current model: " + m.getModel());

        // Apply the delta to our reference model so we can search with proposed
        // changes.
        ModelUtil.applyDeltaAddition(model, addition);

        log.debug("[processDeltaAddition] addition model: " + ModelUtil.marshalModel(model));

        // We model connections as mrs:SwitchingSubnet objects so query the
        // addition model for all those provided.
        ResultSet ssSet = ModelUtil.getResourcesOfType(addition, Mrs.SwitchingSubnet);
        if (!ssSet.hasNext()) {
            log.debug("[processDeltaAddition] no SwitchingSubnet found so ignoring addition, deletaId = {}", deltaId);
            return commits;
        }

        // We will treat each mrs:SwitchingSubnet as an independent reservation in NSI.
        while (ssSet.hasNext()) {
            QuerySolution querySolution = ssSet.next();

            // Get the SwitchingSubnet resource.
            Resource switchingSubnet = querySolution.get("resource").asResource();
            log.debug("[processDeltaAddition] SwitchingSubnet: " + switchingSubnet.getURI());

            // Get the existDruing lifetime object if it exists so we can model a schedule.
            Optional<Statement> existsDuring = Optional.ofNullable(switchingSubnet.getProperty(Nml.existsDuring));
            NmlExistsDuring ssExistsDuring;
            if (existsDuring.isPresent()) {
                // We have an existsDuring resource specifying the schedule time.
                Resource existsDuringRef = existsDuring.get().getResource();
                log.debug("[processDeltaAddition] existsDuringRef: " + existsDuringRef.getURI());

                ssExistsDuring = new NmlExistsDuring(existsDuringRef);
            } else {
                // We need to create our own schedule using the defaults.
                ssExistsDuring = new NmlExistsDuring(switchingSubnet.getURI() + ":existsDuring");
            }

            // We need the associated parent SwitchingService resource to determine
            // the ServiceDefinition that holds the serviceType.
            Statement belongsTo = switchingSubnet.getProperty(Nml.belongsTo);
            Resource switchingServiceRef = belongsTo.getResource();
            log.debug("[processDeltaAddition] SwitchingServiceRef: " + switchingServiceRef.getURI());

            // Get the full SwitchingService definition from the merged model.
            Resource switchingService = ModelUtil.getResourceOfType(model, switchingServiceRef, Nml.SwitchingService);
            if (switchingService == null) {
                throw new IllegalArgumentException(
                        "Could not find referenced switching service " + switchingServiceRef.getURI());
            }
            log.debug("[processDeltaAddition] SwitchingService: " + switchingService.getURI());

            // Now we need the ServiceDefinition associated with this SwitchingService.
            Statement hasServiceDefinition = switchingService.getProperty(Sd.hasServiceDefinition);
            Resource serviceDefinitionRef = hasServiceDefinition.getResource();
            log.debug("[processDeltaAddition] serviceDefinitionRef: " + serviceDefinitionRef.getURI());

            // Get the full ServiceDefinition definition from the merged model.
            Resource serviceDefinition = ModelUtil.getResourceOfType(model, serviceDefinitionRef, Sd.ServiceDefinition);
            log.debug("[processDeltaAddition] ServiceDefinition: " + serviceDefinition.getURI());

            Statement serviceTypeRef = serviceDefinition.getProperty(Sd.serviceType);
            log.debug("[processDeltaAddition] serviceType: " + serviceTypeRef.getString());

            // We currently know about the EVTS p2p service.
            List<StpHolder> stps = new ArrayList<>();

            String serviceType = serviceTypeRef.getString();
            if (Nsi.NSI_SERVICETYPE_EVTS.equalsIgnoreCase(serviceType)
                    || Nsi.NSI_SERVICETYPE_L2_LB_ES.equalsIgnoreCase(serviceType)) {
                // Find the ports that are part of this SwitchingSubnet and build NSI STP
                // identifiers for the service.
                StmtIterator listProperties = switchingSubnet.listProperties(Nml.hasBidirectionalPort);
                while (listProperties.hasNext()) {
                    Statement hasBidirectionalPort = listProperties.next();
                    Resource biRef = hasBidirectionalPort.getResource();
                    log.debug("[processDeltaAddition] bi member: " + biRef.getURI());

                    Resource biChild = ModelUtil.getResourceOfType(addition, biRef, Nml.BidirectionalPort);
                    if (biChild == null) {
                        log.error("[processDeltaAddition] Requested BidirectionalPort does not exist {}",
                                biRef.getURI());
                        throw new IllegalArgumentException(
                                "Requested BidirectionalPort does not exist " + biRef.getURI());
                    }

                    log.debug("[processDeltaAddition] biChild: " + biChild.getURI());

                    MrsBandwidthService bws = new MrsBandwidthService(biChild, addition);

                    log.debug("[processDeltaAddition] BandwidthService: {}", bws.getId());
                    log.debug("[processDeltaAddition] type: {}", bws.getBandwidthType());
                    log.debug("[processDeltaAddition] maximumCapacity: {} {}", bws.getMaximumCapacity(), bws.getUnit());
                    log.debug("[processDeltaAddition] maximumCapacity: {} mbps",
                            MrsUnits.normalize(bws.getMaximumCapacity(), bws.getUnit(), MrsUnits.mbps));

                    // The "guaranteedCapped" BandwidthService maps to the NSI_SERVICETYPE_EVTS
                    // service so
                    // we need to verify this is a valid request.
                    if (MrsBandwidthType.guaranteedCapped != bws.getBandwidthType()
                            && MrsBandwidthType.bestEffort != bws.getBandwidthType()) {
                        String error = "Requested BandwidthService type = " + bws.getBandwidthType()
                                + " not supported by SwitchingService = " + switchingService.getURI() + " on portId = "
                                + biRef.getURI();
                        log.error("[processDeltaAddition] {}.", error);
                        throw new IllegalArgumentException(error);
                    }

                    // Now determine if there is an independent existsDuring object.
                    String childExistsDuringId = ssExistsDuring.getId();
                    existsDuring = Optional.ofNullable(biChild.getProperty(Nml.existsDuring));
                    if (existsDuring.isPresent()) {
                        Resource childExistsDuringRef = existsDuring.get().getResource();
                        log.debug("[processDeltaAddition] childExistsDuringRef: " + childExistsDuringRef.getURI());
                        if (!ssExistsDuring.getId().contentEquals(childExistsDuringRef.getURI())) {
                            // We have a different existsDuring reference than our SwitchingSubnet.
                            childExistsDuringId = childExistsDuringRef.getURI();
                        }
                    }

                    // Now get the label for this port.
                    Statement labelRef = biChild.getProperty(Nml.hasLabel);
                    Resource label = ModelUtil.getResourceOfType(addition, labelRef.getResource(), Nml.Label);

                    NmlLabel nmlLabel = new NmlLabel(label);
                    SimpleLabel simpleLabel = nmlLabel.getSimpleLabel();

                    Resource parentBi = ModelUtil.getParentBidirectionalPort(model, biChild);
                    log.debug("[processDeltaAddition] parentBi: " + parentBi.getURI());

                    SimpleStp stp = new SimpleStp(parentBi.getURI(), simpleLabel);
                    log.debug("[processDeltaAddition] stpId: {}", stp.getStpId());

                    stps.add(new StpHolder(biChild.getURI(), stp, bws, childExistsDuringId, label.getURI()));
                }

                // We need exactly two ports for our point-to-point connection.
                if (stps.size() != 2) {
                    log.error("[processDeltaAddition] SwitchingSubnet contained {} ports.", stps.size());
                    throw new IllegalArgumentException(
                            "SwitchingSubnet contained incorrect number of ports (" + stps.size() + ").");
                }

                // Populate the NSI CS message with p2ps service.
                StpHolder src = stps.get(0);
                StpHolder dst = stps.get(1);

                // Normalize bandidth to mbps for the P2PS request.
                long srcBw = MrsUnits.normalize(src.getBw().getMaximumCapacity(), src.getBw().getUnit(), MrsUnits.mbps);
                long dstBw = MrsUnits.normalize(dst.getBw().getMaximumCapacity(), dst.getBw().getUnit(), MrsUnits.mbps);

                P2PServiceBaseType p2ps = new ObjectFactory().createP2PServiceBaseType();
                p2ps.setCapacity((srcBw > dstBw ? dstBw : srcBw));
                p2ps.setDirectionality(DirectionalityType.BIDIRECTIONAL);
                p2ps.setSymmetricPath(Boolean.TRUE);
                p2ps.setSourceSTP(src.getStp().getStpId());
                p2ps.setDestSTP(dst.getStp().getStpId());

                // Base the reservation off of the specified existsDuring criteria.
                ScheduleType sch = CS_FACTORY.createScheduleType();
                XMLGregorianCalendar paddedStart = ssExistsDuring.getPaddedStart();
                if (paddedStart != null) {
                    sch.setStartTime(CS_FACTORY.createScheduleTypeStartTime(paddedStart));
                }

                if (ssExistsDuring.getEnd() != null) {
                    sch.setEndTime(CS_FACTORY.createScheduleTypeEndTime(ssExistsDuring.getEnd()));
                }

                ReservationRequestCriteriaType rrc = CS_FACTORY.createReservationRequestCriteriaType();
                rrc.setVersion(0);
                rrc.setSchedule(sch);
                rrc.setServiceType(Nsi.NSI_SERVICETYPE_EVTS);
                rrc.getAny().add(P2PS_FACTORY.createP2Ps(p2ps));

                ReserveType r = CS_FACTORY.createReserveType();
                r.setGlobalReservationId(switchingSubnet.getURI());
                r.setDescription("deltaId+" + deltaId + ":uuid+" + UUID.randomUUID().toString());
                r.setCriteria(rrc);

                // Now store the mapping for this SwitchingSubnet.
                ConnectionMap cm = new ConnectionMap();
                cm.setDescription(r.getDescription());
                cm.setDeltaId(deltaId);
                cm.setSwitchingSubnetId(switchingSubnet.getURI());
                cm.setExistsDuringId(ssExistsDuring.getId());
                cm.setServiceType(serviceType);

                StpMapping smSrc = new StpMapping(src.getStp().getStpId(), src.getMrsPortId(), src.getMrsLabelId(),
                        src.getBw().getId(), src.getNmlExistsDuringId());
                cm.getMap().add(smSrc);

                StpMapping smDst = new StpMapping(dst.getStp().getStpId(), dst.getMrsPortId(), dst.getMrsLabelId(),
                        dst.getBw().getId(), dst.getNmlExistsDuringId());
                cm.getMap().add(smDst);

                ConnectionMap stored = cmRepo.save(cm);

                log.debug("[processDeltaAddition] storing connectionMap = {}", stored);

                String correlationId = Helper.getUUID();
                // CommonHeaderType requestHeader =
                // NsiHeader.builder().correlationId(correlationId)
                // .providerNSA(providerNSA).requesterNSA(topoID).replyTo(allowedRequesters)
                // .getRequestHeaderType();

                // Holder<CommonHeaderType> header = new Holder<>();
                // header.value = requestHeader;

                // Add this to the operation map to track progress.
                // Operation op = new Operation();
                // op.setOperation(OperationType.reserve);
                // op.setState(StateType.reserving);
                // op.setCorrelationId(correlationId);
                // op.setReservation(createReservation(r));
                // operationMap.store(op);

                correlationIds.add(correlationId);

                // // Issue the NSI reservation request.
                // try {
                // log.debug("[processDeltaAddition] issuing reserve operation correlationId =
                // {}", correlationId);
                // ClientUtil nsiClient = new
                // ClientUtil(nsiProperties.getProviderConnectionURL());
                // ReserveResponseType response = nsiClient.getProxy().reserve(r, header);

                // // Add connectionId to the list we need to commit.
                // commits.add(response.getConnectionId());

                // log.debug("[processDeltaAddition] issued reserve operation correlationId =
                // {}, connectionId = {}",
                // correlationId, response.getConnectionId());
                // } catch (SOAPFaultException ex) {
                // // TODO: Consider whether we should unwrap any NSI reservations that were
                // // successful.
                // // For now just delete the correlationId we added.
                // operationMap.delete(correlationIds);
                // log.error(
                // "[processDeltaAddition] Failed to send NSI CS reserve message, correlationId
                // = {}, SOAP Fault = {}",
                // requestHeader.getCorrelationId(), ex.getFault().toString());
                // throw ex;
                // } catch (ServiceException ex) {
                // // TODO: Consider whether we should unwrap any NSI reservations that were
                // // successful.
                // // For now just delete the correlationId we added.
                // operationMap.delete(correlationIds);
                // log.error(
                // "[processDeltaAddition] Failed to send NSI CS reserve message, correlationId
                // = {}, errorId = {}, text = {}",
                // requestHeader.getCorrelationId(), ex.getFaultInfo().getErrorId(),
                // ex.getFaultInfo().getText());
                // throw ex;
                // }
            } else {
                log.error("[processDeltaAddition] serviceType not supported {}", serviceTypeRef.getString());
                throw new IllegalArgumentException("serviceType not supported " + serviceTypeRef.getString());
            }
        }

        // Wait for our outstanding reserve operations to complete (or fail).
        // We are expecting an asynchronous reserveConfirm in response.
        // waitForOperations(correlationIds);

        // Return the list of connections ids that will need to be commited.
        return commits;
    }

    // /**
    // * Wait for queued NSI operations to complete by waiting on a shared semaphore
    // * between this thread and the NSI CS callback thread.
    // *
    // * @param correlationIds List of the outstand correlationIds the will need to
    // * complete.
    // *
    // * @return Will return an exception (the last encountered) if one has
    // occurred.
    // */
    // private void waitForOperations(List<String> correlationIds)
    // throws ServiceException, IllegalArgumentException, TimeoutException {
    // Optional<Exception> exception = Optional.empty();
    // for (String id : correlationIds) {
    // log.info("[CsProvider] waiting for completion of correlationId = {}", id);
    // if (operationMap.wait(id)) {
    // Operation op = operationMap.delete(id);

    // log.info("[CsProvider] operation {} completed, correlationId = {}",
    // op.getOperation(), id);

    // if (op.getOperation() == OperationType.reserve && op.getState() !=
    // StateType.reserved) {
    // log.error("[CsProvider] operation failed to reserve, correlationId = {},
    // state = {}", id,
    // op.getState(), op.getException());

    // if (op.getException() != null) {
    // exception = Optional.of(new ServiceException("Operation failed to reserve",
    // op.getException()));
    // } else {
    // exception = Optional.of(new IllegalArgumentException(
    // "Operation failed to reserve, correlationId = " + id + ", state = " +
    // op.getState()));
    // }
    // }
    // } else {
    // log.error("[CsProvider] timeout, failed to get response for correlationId =
    // {}", id);
    // Operation op = operationMap.delete(id);
    // StateType state = StateType.unknown;
    // if (op != null) {
    // state = op.getState();
    // }
    // exception = Optional.of(new TimeoutException(
    // "Operation failed to reserve, correlationId = " + id + ", state = " +
    // state));
    // }
    // }

    // // Do I really care about the specific exception?
    // if (exception.isPresent()) {
    // Exception ex = exception.get();
    // if (ex instanceof ServiceException) {
    // throw ServiceException.class.cast(ex);
    // } else if (ex instanceof IllegalArgumentException) {
    // throw IllegalArgumentException.class.cast(ex);
    // } else if (ex instanceof TimeoutException) {
    // throw TimeoutException.class.cast(ex);
    // }
    // }
    // }
}
