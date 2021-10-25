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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.xml.datatype.DatatypeConfigurationException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import net.es.oscars.pce.PceService;
import net.es.oscars.resv.db.ConnectionRepository;
import net.es.oscars.resv.ent.Connection;
import net.es.oscars.resv.ent.EroHop;
import net.es.oscars.resv.enums.BuildMode;
import net.es.oscars.resv.enums.ConnectionMode;
import net.es.oscars.resv.enums.Phase;
import net.es.oscars.resv.enums.State;
import net.es.oscars.resv.svc.ConnService;
import net.es.oscars.sense.db.SENSEDeltaRepository;
import net.es.oscars.sense.definitions.ExistsDuring;
import net.es.oscars.sense.definitions.Mrs;
import net.es.oscars.sense.definitions.Nml;
import net.es.oscars.sense.definitions.Sd;
import net.es.oscars.sense.definitions.mrml.MrsBandwidthService;
import net.es.oscars.sense.definitions.mrml.MrsBandwidthType;
import net.es.oscars.sense.definitions.mrml.MrsUnits;
import net.es.oscars.sense.model.DeltaConnectionData;
import net.es.oscars.sense.model.DeltaTranslation;
import net.es.oscars.sense.model.entities.SENSEDelta;
import net.es.oscars.sense.model.entities.SENSEModel;
import net.es.oscars.web.beans.ConnectionFilter;
import net.es.oscars.web.beans.Interval;
import net.es.oscars.web.beans.PceRequest;
import net.es.oscars.web.beans.PceResponse;
import net.es.oscars.web.simple.Fixture;
import net.es.oscars.web.simple.Junction;
import net.es.oscars.web.simple.Pipe;
import net.es.oscars.web.simple.SimpleConnection;
import net.es.oscars.web.simple.SimpleTag;
import net.es.oscars.web.simple.Validity;

/**
 * A provider implementing MRML delta operations using the NSI Connection
 * Service.
 *
 * @author hacksaw
 */
@Slf4j
@Component
public class SENSEConnectionService {

    @Autowired
    private ConnService connSvc;

    @Autowired
    private PceService pceSvc;

    @Autowired
    private SENSEDeltaRepository deltaRepository;

    @Autowired
    private ConnectionRepository connRepo;

    @Value("${nsi.provider-nsa}")
    private String providerNSA;
    @Value("${nsi.allowed-requesters}")
    private String allowedRequesters;
    @Value("${nml.topo-id}")
    private String topoID;
    @Value("${resv.timeout}")
    private Integer resvTimeout;

    private ObjectMapper mapper = new ObjectMapper();

    public void processDelta(String username, SENSEModel model, SENSEDelta delta, Optional<Model> reduction,
            Optional<Model> addition) throws Exception {
        String deltaId = delta.getUuid();
        log.debug("[processDelta] start deltaId = {}", deltaId);

        ArrayList<DeltaConnectionData> commits = null;
        Set<String> terminates = null;

        // We process the reduction first.
        if (reduction.isPresent() && !reduction.get().isEmpty()) {
            log.debug("[processDelta] processing reduction, deltaId = {}", deltaId);
            terminates = processDeltaReduction(model, deltaId, reduction.get());
        }

        // Now the addition.
        if (addition.isPresent() && !addition.get().isEmpty()) {
            log.debug("[processDelta] processing addition, deltaId = {}", deltaId);
            commits = processDeltaAddition(model, deltaId, addition.get());
            // Reserve all connections, breaking up if any fail.
            for (DeltaConnectionData connPack : commits) {
                SimpleConnection connRequest = connPack.getConn();
                String connID = connSvc.generateConnectionId();
                log.debug("[processDelta] creating conn: {}", connID);
                if (connID.length() == 4) {
                    connRequest.setConnectionId(connID);
                    Validity v = connSvc.validate(connRequest, ConnectionMode.NEW);
                    if (!v.isValid()) {
                        connRequest.setValidity(v);
                        log.info("[processDelta] Connection {} failed to validate: {} ", connRequest.getConnectionId(),
                                v.getMessage());
                        throw new Exception("Validation error: " + v.getMessage());
                    } else {
                        connRequest.setUsername("admin");
                        Instant exp = Instant.now().plus(resvTimeout, ChronoUnit.SECONDS);
                        long secs = exp.toEpochMilli() / 1000L;
                        connRequest.setHeldUntil((int) secs);

                        String connectionId = connRequest.getConnectionId();

                        Optional<Connection> maybeConnection = connRepo.findByConnectionId(connectionId);
                        if (maybeConnection.isPresent()) {
                            Connection prev = maybeConnection.get();
                            if (!prev.getPhase().equals(Phase.HELD)) {
                                throw new IllegalArgumentException("connection not in HELD phase");
                            }
                            log.info("overwriting previous connection for " + connectionId);
                            connSvc.updateConnection(connRequest, prev);
                            connRepo.save(prev);
                        } else {
                            log.info("saving new connection " + connectionId);
                            Connection c = connSvc.toNewConnection(connRequest);
                            connRepo.save(c);
                        }
                        log.debug("[processDelta] held conn: {}", connID);
                    }
                }
            }
        }

        // Update the list of connections generated.
        if (commits != null) {
            List<String> commitList = new ArrayList<>();
            for (DeltaConnectionData connPack : commits) {
                SimpleConnection conn = connPack.getConn();
                commitList.add(conn.getConnectionId());
            }
            delta.setCommits(commitList.toArray(new String[commitList.size()]));
        } else {
            delta.setCommits(new String[0]);
        }

        if (terminates != null) {
            delta.setTerminates(terminates.toArray(new String[terminates.size()]));
        } else {
            delta.setTerminates(new String[0]);
        }
        deltaRepository.save(delta);

        log.debug("[processDelta] end deltaId = {}", deltaId);
    }

    private Set<String> processDeltaReduction(SENSEModel m, String deltaId, Model reduction) {
        log.debug("[processDeltaReduction] start");

        // The list of connection ids we will need terminate in the delta commit.
        Set<String> ret = new HashSet<>();
        // ? Perhaps not necessary.
        // Get the associated model. Process the reduction.
        // Model model = ModelUtil.unmarshalModel(m.getModel());
        // ModelUtil.applyDeltaReduction(model, reduction);

        // We model connections as mrs:SwitchingSubnet objects so query the
        // reduction model for all those provided. We will just delete these.
        ResultSet ssSet = ModelUtil.getResourcesOfType(reduction, Mrs.SwitchingSubnet);
        if (!ssSet.hasNext()) {
            log.debug("[processDeltaAddition] no SwitchingSubnet found so ignoring addition, deletaId = {}", deltaId);
            return ret;
        }
        while (ssSet.hasNext()) {
            // Iterate through connections.
            QuerySolution querySolution = ssSet.next();
            Resource switchingSubnet = querySolution.get("resource").asResource();
            log.debug("[processDeltaReduction] SwitchingSubnet: " + switchingSubnet.getURI());

            StmtIterator listProperties = switchingSubnet.listProperties(Nml.hasBidirectionalPort);
            while (listProperties.hasNext()) {
                // Gather port model info.
                Statement hasBidirectionalPort = listProperties.next();
                Resource biRef = hasBidirectionalPort.getResource();
                log.debug("[processDeltaReduction] bi member: " + biRef.getURI());
                Resource biChild = ModelUtil.getResourceOfType(reduction, biRef, Nml.BidirectionalPort);
                if (biChild == null) {
                    log.error("[processDeltaReduction] Requested BidirectionalPort does not exist {}", biRef.getURI());
                    throw new IllegalArgumentException("Requested BidirectionalPort does not exist " + biRef.getURI());
                }
                DeltaTranslation portTrans = new DeltaTranslation(topoID, biChild.getURI());
                log.debug("[processDeltaReduction] translation: " + portTrans.toString());
                log.debug("[processDeltaReduction] biChild: " + biChild.getURI());
                //

                // Grab VLAN label information
                Statement labelRef = biChild.getProperty(Nml.hasLabel);
                Resource label = ModelUtil.getResourceOfType(reduction, labelRef.getResource(), Nml.Label);
                int vlan = -1;
                if (label.getProperty(Nml.labeltype).getObject().toString()
                        .contentEquals(Nml.labeltype_Ethernet_Vlan.toString())) {
                    vlan = Integer.parseInt(label.getProperty(Nml.value).getObject().toString());
                }
                //

                // Find the connection that is currently using this port fixture and VLAN.
                ConnectionFilter filter = ConnectionFilter.builder().phase("RESERVED").state(State.ACTIVE).page(1)
                        .sizePerPage(1).ports(Arrays.asList(portTrans.toLabel())).vlans(Arrays.asList(vlan)).build();
                List<Connection> found = connSvc.filter(filter).getConnections().stream()
                        .filter(conn -> conn.getState().equals(State.ACTIVE)).collect(Collectors.toList());
                if (found.size() > 0) {
                    log.debug("[processDeltaReduction] Active connecton found for port {}", portTrans.toLabel());
                    Connection conn = found.get(0);
                    ret.add(conn.getConnectionId());
                } else {
                    // No active connection found.
                    log.debug("[processDeltaReduction] No active connecton found for port {}", portTrans.toLabel());
                }
                //
            }

            log.debug("[processDeltaReduction] done");
        }

        return ret;
    }

    private ArrayList<DeltaConnectionData> processDeltaAddition(SENSEModel m, String deltaId, Model addition)
            throws DatatypeConfigurationException, ServiceException, IllegalArgumentException, TimeoutException,
            Exception {
        log.debug("[processDeltaAddition] start deletaId = {}, modelId = {}", deltaId, m.getId());

        ArrayList<DeltaConnectionData> ret = new ArrayList<>();
        // Get the associated model. Process the addition.
        Model model = ModelUtil.unmarshalModel(m.getModel());
        ModelUtil.applyDeltaAddition(model, addition);

        ResultSet ssSet = ModelUtil.getResourcesOfType(addition, Mrs.SwitchingSubnet);
        if (!ssSet.hasNext()) {
            log.debug("[processDeltaAddition] no SwitchingSubnet found so ignoring addition, deletaId = {}", deltaId);
            return ret;
        }

        while (ssSet.hasNext()) {
            DeltaConnectionData pack = new DeltaConnectionData();
            pack.setDeltaId(deltaId);
            // Iterate through connections.
            QuerySolution querySolution = ssSet.next();

            // Get the SwitchingSubnet resource.
            Resource switchingSubnet = querySolution.get("resource").asResource();
            log.debug("[processDeltaAddition] SwitchingSubnet: " + switchingSubnet.getURI());

            // Get the existDruing lifetime object if it exists so we can model a schedule.
            Optional<Statement> existsDuring = Optional.ofNullable(switchingSubnet.getProperty(Nml.existsDuring));
            ExistsDuring lifetimeDuring;
            if (existsDuring.isPresent()) {
                // We have an existsDuring resource specifying the schedule time.
                Resource existsDuringRef = existsDuring.get().getResource();
                log.debug("[processDeltaAddition] existsDuringRef: " + existsDuringRef.getURI());

                lifetimeDuring = new ExistsDuring(existsDuringRef);
            } else {
                // We need to create our own schedule using the defaults.
                lifetimeDuring = new ExistsDuring(switchingSubnet.getURI() + ":existsDuring");
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

            //
            // >> ITERATE OVER PORTS
            // Create connection scaffold.
            SimpleConnection connRequest = new SimpleConnection();
            connRequest.setDescription("TEST HOLD");
            connRequest.setMode(BuildMode.AUTOMATIC);

            StmtIterator listProperties = switchingSubnet.listProperties(Nml.hasBidirectionalPort);
            while (listProperties.hasNext()) {
                // Gather port model info.
                Statement hasBidirectionalPort = listProperties.next();
                Resource biRef = hasBidirectionalPort.getResource();
                log.debug("[processDeltaAddition] bi member: " + biRef.getURI());
                Resource biChild = ModelUtil.getResourceOfType(addition, biRef, Nml.BidirectionalPort);
                if (biChild == null) {
                    log.error("[processDeltaAddition] Requested BidirectionalPort does not exist {}", biRef.getURI());
                    throw new IllegalArgumentException("Requested BidirectionalPort does not exist " + biRef.getURI());
                }
                DeltaTranslation portTrans = new DeltaTranslation(topoID, biChild.getURI());
                log.debug("[processDeltaAddition] translation: " + portTrans.toString());
                log.debug("[processDeltaAddition] biChild: " + biChild.getURI());
                //

                // Check if junction has been added.
                if (!connRequest.getJunctions().stream().anyMatch(o -> o.getDevice().equals(portTrans.getJunction()))) {
                    connRequest.getJunctions().add(new Junction(portTrans.getJunction(), null));
                }
                // Create Fixture scaffold.
                Fixture portFixture = new Fixture();
                portFixture.setJunction(portTrans.getJunction());
                portFixture.setPort(portTrans.getJunction() + ":" + portTrans.getPort());
                //

                // Grab bandwidth info.
                MrsBandwidthService bws = new MrsBandwidthService(biChild, addition);
                log.debug("[processDeltaAddition] BandwidthService: {}", bws.getId());
                log.debug("[processDeltaAddition] type: {}", bws.getBandwidthType());
                log.debug("[processDeltaAddition] maximumCapacity: {} {}", bws.getMaximumCapacity(), bws.getUnit());
                log.debug("[processDeltaAddition] maximumCapacity: {} mbps",
                        MrsUnits.normalize(bws.getMaximumCapacity(), bws.getUnit(), MrsUnits.mbps));
                portFixture.setMbps(
                        Math.toIntExact(MrsUnits.normalize(bws.getMaximumCapacity(), bws.getUnit(), MrsUnits.mbps)));

                portTrans.setBwURN(bws.getId());
                //

                // ?
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
                //

                // Grab VLAN label information
                Statement labelRef = biChild.getProperty(Nml.hasLabel);
                Resource label = ModelUtil.getResourceOfType(addition, labelRef.getResource(), Nml.Label);
                if (label.getProperty(Nml.labeltype).getObject().toString()
                        .contentEquals(Nml.labeltype_Ethernet_Vlan.toString())) {
                    portFixture.setVlan(Integer.parseInt(label.getProperty(Nml.value).getObject().toString()));
                }

                portTrans.setVlanURN(label.getURI());
                //

                // Get potential tag
                Statement tagRef = biChild.getProperty(Mrs.tag);
                if (tagRef != null)
                    portTrans.setTagURN(tagRef.getString());
                //

                log.debug("[processDeltaAddition] portFixture: {}", portFixture);
                pack.getTranslations().put(biChild.getURI(), portTrans);
                connRequest.getFixtures().add(portFixture);
            }

            if (connRequest.getJunctions().size() > 1) {
                List<Junction> junc = connRequest.getJunctions();
                // If request is multi-device, need to add pipes.
                String a = junc.get(0).getDevice();
                String z = junc.get(junc.size() - 1).getDevice();

                PceRequest pceReq = new PceRequest(new Interval(lifetimeDuring.getStart(), lifetimeDuring.getEnd()), a,
                        z, 1000, 1000, null, null);
                PceResponse calc = pceSvc.calculatePaths(pceReq);

                List<String> ero = new ArrayList<>();
                for (EroHop hop : calc.getFits().getAzEro()) {
                    ero.add(hop.getUrn());
                }
                Pipe pipe = Pipe.builder().a(a).z(z).mbps(1000).ero(ero).build();
                connRequest.getPipes().add(pipe);
            }

            // ?
            // Legacy Res code - may not be needed
            // ScheduleType sch = CS_FACTORY.createScheduleType();
            // XMLGregorianCalendar paddedStart = lifetimeDuring.getPaddedStart();
            // if (paddedStart != null) {
            // sch.setStartTime(CS_FACTORY.createScheduleTypeStartTime(paddedStart));
            // }

            // if (lifetimeDuring.getEnd() != null) {
            // sch.setEndTime(CS_FACTORY.createScheduleTypeEndTime(lifetimeDuring.getEnd()));
            // }

            // ReservationRequestCriteriaType rrc =
            // CS_FACTORY.createReservationRequestCriteriaType();
            // rrc.setVersion(0);
            // rrc.setSchedule(sch);
            // rrc.setServiceType(Nsi.NSI_SERVICETYPE_EVTS);

            // ReserveType r = CS_FACTORY.createReserveType();
            // r.setGlobalReservationId(switchingSubnet.getURI());
            // r.setDescription("deltaId+" + deltaId + ":uuid+" +
            // UUID.randomUUID().toString());
            // r.setCriteria(rrc);
            //

            // Add tags to connection request.
            List<SimpleTag> tagList = new ArrayList<>();
            tagList.add(new SimpleTag("SENSE_CONNECTION_ID", switchingSubnet.getURI()));

            Map<String, String> duringMap = new HashMap<>();
            duringMap.put("urn", lifetimeDuring.getId());
            duringMap.put("start", lifetimeDuring.getStart().toString());
            duringMap.put("end", lifetimeDuring.getEnd().toString());
            tagList.add(new SimpleTag("SENSE_CONNECTION_DURING", mapper.writeValueAsString(duringMap)));

            pack.getTranslations().forEach((k, v) -> {
                Map<String, String> transMap = new HashMap<>();
                transMap.put("port", k);
                transMap.put("vlan", v.getVlanURN());
                transMap.put("bw", v.getBwURN());
                transMap.put("tag", v.getTagURN());

                transMap.put("oscars", v.getJunction() + ":" + v.getPort());
                try {
                    tagList.add(SimpleTag.builder().category("SENSE_TRANSLATION")
                            .contents(mapper.writeValueAsString(transMap)).build());
                } catch (JsonProcessingException e) {
                }
            });
            connRequest.setTags(tagList);
            //

            connRequest.setBegin(Math.toIntExact(lifetimeDuring.getStart().getEpochSecond()));
            connRequest.setEnd(Math.toIntExact(lifetimeDuring.getEnd().getEpochSecond()));

            pack.setConn(connRequest);
            log.debug("[processDeltaAddition] connRequest: {}", connRequest);
            ret.add(pack);
        }

        // Return the list of connections ids that will need to be commited.
        return ret;
    }

}
