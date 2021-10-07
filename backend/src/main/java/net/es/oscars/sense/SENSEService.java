package net.es.oscars.sense;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;

import com.google.common.base.Strings;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.es.oscars.app.Startup;
import net.es.oscars.app.exc.PCEException;
import net.es.oscars.app.exc.PSSException;
import net.es.oscars.app.exc.StartupException;
import net.es.oscars.nsi.svc.NsiPopulator;
import net.es.oscars.nsi.svc.NsiService;
import net.es.oscars.resv.ent.Connection;
import net.es.oscars.resv.svc.ConnService;
import net.es.oscars.resv.svc.ResvService;
import net.es.oscars.sense.db.SENSEDeltaRepository;
import net.es.oscars.sense.db.SENSEModelRepository;
import net.es.oscars.sense.definitions.Mrs;
import net.es.oscars.sense.definitions.Nml;
import net.es.oscars.sense.definitions.Nsi;
import net.es.oscars.sense.definitions.Sd;
import net.es.oscars.sense.model.DeltaModel;
import net.es.oscars.sense.model.DeltaRequest;
import net.es.oscars.sense.model.DeltaState;
import net.es.oscars.sense.model.entities.SENSEDelta;
import net.es.oscars.sense.model.entities.SENSEModel;
import net.es.oscars.sense.tools.ModelUtil;
import net.es.oscars.sense.tools.SENSEConnectionService;
import net.es.oscars.topo.beans.IntRange;
import net.es.oscars.topo.beans.Topology;
import net.es.oscars.topo.ent.Device;
import net.es.oscars.topo.ent.Port;
import net.es.oscars.topo.enums.Layer;
import net.es.oscars.topo.svc.TopoService;
import net.es.oscars.web.beans.ConnChangeResult;
import net.es.oscars.web.beans.ConnException;

@Transactional
@Service
@Data
@Slf4j
public class SENSEService {
    @Value("${nml.topo-id}")
    private String topoId;

    @Value("${nml.topo-name}")
    private String topoName;

    @Autowired
    private ConnService connSvc;

    @Autowired
    private SENSEModelRepository modelRepository;

    @Autowired
    private SENSEDeltaRepository deltaRepo;

    @Autowired
    private SENSEConnectionService driverCS;

    @Autowired
    private TopoService topoService;

    @Autowired
    private ResvService resvService;

    @Autowired
    private NsiPopulator nsiPopulator;

    @Autowired
    private NsiService nsiService;

    @Autowired
    private Startup startup;

    public SENSEModel buildModel() throws StartupException {
        if (startup.isInStartup()) {
            throw new StartupException("OSCARS starting up");
        } else if (startup.isInShutdown()) {
            throw new StartupException("OSCARS shutting down");
        }
        if (!nsiPopulator.isLoaded()) {
            throw new StartupException("NSI files not loaded");
        }

        if (topoService.getCurrent() == null) {
            throw new InternalError("no valid topology version");
        }
        // Version v = topoService.getCurrent();
        DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_INSTANT;
        Instant now = Instant.now();

        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_MICRO_RULE_INF);
        // Manage root topology
        Resource resTopology = RdfOwl.createResource(model, topoId, Nml.Topology);
        model.add(model.createStatement(resTopology, Nml.version, dateFormatter.format(now)));
        model.add(model.createStatement(resTopology, Nml.name, topoName));

        Resource resLifetime = RdfOwl.createResource(model, resTopology.getURI() + ":lifetime", Nml.Lifetime);
        model.add(model.createStatement(resTopology, Nml.existsDuring, resLifetime));
        model.add(model.createStatement(resLifetime, Nml.start, dateFormatter.format(now)));
        model.add(model.createStatement(resLifetime, Nml.end, dateFormatter.format(now.plusSeconds(21600))));

        // Manage switching service
        Resource resSwSvc = RdfOwl.createResource(model, resTopology.getURI() + ":l2switching", Nml.SwitchingService);
        model.add(model.createStatement(resSwSvc, Nml.encoding, RdfOwl.labelTypeVLAN));
        model.add(model.createStatement(resSwSvc, Nml.labelSwapping, "true"));
        model.add(model.createStatement(resTopology, Nml.hasService, resSwSvc));

        // Resource resSwSvcDef = RdfOwl.createResource(model, resSwSvc.getURI() +
        // ":sd+l2mpes", Sd.ServiceDefinition);
        // model.add(model.createStatement(resSwSvcDef, Sd.serviceType,
        // Sd.URI_SvcDef_L2MpEs));
        // model.add(model.createStatement(resSwSvc, Sd.hasServiceDefinition,
        // resSwSvcDef));

        Resource resSwSvcDefEVTS = RdfOwl.createResource(model, resSwSvc.getURI() + ":sd+evts", Sd.ServiceDefinition);
        model.add(model.createStatement(resSwSvcDefEVTS, Sd.serviceType, Nsi.NSI_SERVICETYPE_EVTS));
        model.add(model.createStatement(resSwSvc, Sd.hasServiceDefinition, resSwSvcDefEVTS));
        //

        Topology topology = topoService.currentTopology();
        List<Port> edgePorts = new ArrayList<>();
        for (Device d : topology.getDevices().values()) {
            for (Port p : d.getPorts()) {
                if (p.getCapabilities().contains(Layer.ETHERNET) && !p.getReservableVlans().isEmpty()) {
                    boolean allow = false;
                    for (String filter : nsiPopulator.getFilter()) {
                        if (p.getUrn().contains(filter)) {
                            allow = true;
                        }

                    }
                    if (allow) {
                        edgePorts.add(p);
                    }
                }
            }
        }

        for (Port edge : edgePorts) {
            String nsiUrn = nsiService.nsiUrnFromInternal(edge.getUrn());

            // >> Port node
            // Port general statements
            Resource resPort = RdfOwl.createResource(model, nsiUrn, Nml.BidirectionalPort);
            model.add(model.createStatement(resPort, Nml.name, edge.getUrn()));
            model.add(model.createStatement(resPort, Nml.belongsTo, resTopology));
            model.add(model.createStatement(resPort, Nml.encoding, Nml.labeltype_Ethernet));
            // Add root relationships
            model.add(model.createStatement(resTopology, Nml.hasBidirectionalPort, resPort));
            model.add(model.createStatement(resSwSvc, Nml.hasBidirectionalPort, resPort));
            // <<
            // >> Generate VLAN label group
            List<String> vlanParts = new ArrayList<>();
            for (IntRange r : edge.getReservableVlans()) {
                if (r.getFloor().equals(r.getCeiling())) {
                    vlanParts.add(r.getFloor().toString());
                } else {
                    vlanParts.add(r.getFloor() + "-" + r.getCeiling());
                }
            }
            String vlans = String.join(",", vlanParts);

            // NsiPeering peering =
            // nsiPopulator.getPlusPorts().get(edge.getUrn().replace("/", "_"));
            // if (peering != null) {
            // System.out.println(peering);
            // }

            Resource resVLAN = RdfOwl.createResource(model, nsiUrn + ":vlan", Nml.LabelGroup);
            model.add(model.createStatement(resVLAN, Nml.labeltype, Nml.labeltype_Ethernet_Vlan));
            model.add(model.createStatement(resVLAN, Nml.values, vlans));
            // Add relationship
            model.add(model.createStatement(resPort, Nml.hasLabelGroup, resVLAN));
            model.add(model.createStatement(resVLAN, Nml.belongsTo, resPort));
            // <<
            // >> Generate bandwidth service
            Resource resBandwidth = RdfOwl.createResource(model, nsiUrn + ":BandwidthService", Mrs.BandwidthService);

            String GRANULARITY = "1"; // 1 for 1mbps.
            // Bandwidth general statements
            model.add(model.createStatement(resBandwidth, Mrs.type, "guaranteedCapped"));
            model.add(model.createStatement(resBandwidth, Mrs.unit, "mbps"));
            model.add(model.createStatement(resBandwidth, Mrs.granularity, GRANULARITY));

            // Bandwidth capacity
            model.add(model.createStatement(resBandwidth, Mrs.availableCapacity,
                    String.valueOf(edge.getReservableEgressBw())));

            // Add relationship
            model.add(model.createStatement(resPort, Nml.hasService, resBandwidth));
            model.add(model.createStatement(resBandwidth, Nml.belongsTo, resPort));
            // <<
            // break;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        model.write(baos, "TURTLE");
        SENSEModel newModel = new SENSEModel(UUID.randomUUID().toString(), now.toString(), baos.toString(),
                resTopology.getURI());
        modelRepository.save(newModel);
        return newModel;
    }

    public Future<Optional<DeltaModel>> propagateDelta(DeltaRequest deltaRequest, String username)
            throws StartupException, Exception {
        Optional<DeltaModel> response = Optional.empty();
        log.info("[propagateDelta] processing deltaId = {}", deltaRequest.getId());

        // Make sure the referenced referencedModel has not expired.
        Optional<SENSEModel> referencedModel = modelRepository.findById(deltaRequest.getModelId());
        if (!referencedModel.isPresent()) {
            log.error("[SENSEService] specified model not found, modelId = {}.", deltaRequest.getModelId());
            // return new AsyncResult<>(response);
        }

        // We need to apply the reduction and addition to the current referenced model.
        SENSEModel currentModel;
        Optional<SENSEModel> currentModelOpt = modelRepository.findFirstByOrderByCreationTimeDesc();
        if (!currentModelOpt.isPresent()) {
            currentModel = buildModel();
            // log.error("[SENSEService] Could not find current model for networkId = {}",
            // topoId);
            // return new AsyncResult<>(response);
        } else {
            currentModel = currentModelOpt.get();
            log.info("[propagateDelta] ??? {}", currentModel.getCreationTime());
        }

        // Fallback for modeling
        if (!referencedModel.isPresent()) {
            referencedModel = currentModelOpt;
        }

        try {
            // Get the referencedModel on which we apply the changes.
            org.apache.jena.rdf.model.Model rdfModel = ModelUtil.unmarshalModel(currentModel.getModel());

            // Apply the delta reduction.
            Optional<org.apache.jena.rdf.model.Model> reduction = Optional.empty();
            if (!Strings.isNullOrEmpty(deltaRequest.getReduction())) {
                reduction = Optional.ofNullable(ModelUtil.unmarshalModel(deltaRequest.getReduction()));
                reduction.ifPresent(r -> ModelUtil.applyDeltaReduction(rdfModel, r));
            }

            // Apply the delta addition.
            Optional<org.apache.jena.rdf.model.Model> addition = Optional.empty();
            if (!Strings.isNullOrEmpty(deltaRequest.getAddition())) {
                addition = Optional.ofNullable(ModelUtil.unmarshalModel(deltaRequest.getAddition()));
                addition.ifPresent(a -> ModelUtil.applyDeltaAddition(rdfModel, a));
            }

            // Create and store a delta object representing this request.

            SENSEDelta delta = SENSEDelta.builder().uuid(deltaRequest.getId()).modelId(deltaRequest.getModelId())
                    .lastModified(System.currentTimeMillis())._state(DeltaState.Accepting)
                    .addition(deltaRequest.getAddition()).reduction(deltaRequest.getReduction())
                    ._result(ModelUtil.marshalModel(rdfModel)).commits(new String[0]).terminates(new String[0]).build();
            String id = deltaRepo.save(delta).getUuid();

            log.info("[SENSEService] stored deltaId = {}", id);

            // Now process the delta which may result in an asynchronous
            // modification to the delta. Keep track of the connectionId
            // so that we can commit connections associated with this
            // delta.
            try {
                driverCS.processDelta(username, referencedModel.get(), delta, reduction, addition);
            } catch (Exception ex) {
                log.error("[SENSEService] SENSE CS processing of delta failed,  deltaId = {}", delta.getUuid(), ex);
                delta = deltaRepo.findByUuid(id).get();
                delta.setState(DeltaState.Failed);
                deltaRepo.save(delta);

                throw ex;
            }

            // Read the delta again then update if needed. The orchestrator should
            // not have a reference yet but just in case.
            delta = deltaRepo.findByUuid(id).get();
            if (delta.getState() == DeltaState.Accepting) {
                delta.setState(DeltaState.Accepted);
            }

            delta.setLastModified(System.currentTimeMillis());
            deltaRepo.save(delta);

            // Sent back the delta created to the orchestrator.
            ZonedDateTime modifiedTime = Instant.ofEpochMilli(delta.getLastModified()).atZone(ZoneId.systemDefault());

            DeltaModel deltaResponse = new DeltaModel();
            deltaResponse.setId(delta.getUuid());
            deltaResponse.setState(delta.getState());
            deltaResponse.setLastModified(modifiedTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            deltaResponse.setModelId(currentModel.getId());
            deltaResponse.setReduction(delta.getReduction());
            deltaResponse.setAddition(delta.getAddition());
            deltaResponse.setResult(delta.getResult());

            return new AsyncResult<>(Optional.of(deltaResponse));
        } catch (Exception ex) {
            log.error("[SenseService] propagateDelta failed for modelId = {}", deltaRequest.getModelId(), ex);
            return new AsyncResult<>(response);
        }
    }

    public SENSEDelta commitDelta(SENSEDelta delta) throws StartupException {
        // Retrieve delta and associated connections.
        List<String> connections = new ArrayList<>();
        connections.addAll(Arrays.asList(delta.getCommits()));
        connections.addAll(Arrays.asList(delta.getTerminates()));

        if (connections.size() == 0) {
            return null;
        }

        delta.setState(DeltaState.Committing);
        deltaRepo.save(delta);

        // Iterate over connections and watch for errors.
        for (String connID : connections) {
            ConnChangeResult connRes = commitConnection(connID);
            if (connRes == null) {
                log.error("[commitDelta] Connection {} commit error. Ending process.", connID);

                delta.setState(DeltaState.Failed);
                deltaRepo.save(delta);
            } else {
                log.debug("[commitDelta] Connection {} change result.\n{}", connID, connRes);

                delta.setState(DeltaState.Committed);
                deltaRepo.save(delta);

                buildModel();
            }
        }
        return delta;
    }

    public ConnChangeResult commitConnection(String connID) {
        try {
            Connection c = connSvc.findConnection(connID);
            // if (!c.getUsername().equals(username)) {
            // c.setUsername(username);
            // }

            log.info("[commitConnection] Committing connection " + connID);
            return connSvc.commit(c);
        } catch (PSSException | PCEException | ConnException ex) {
            log.info("[commitConnection] Error committing " + connID, ex);
            return null;
        } catch (IllegalArgumentException ex) {
            log.info("[commitConnection] Error committing " + connID, ex);
            return null;
        }
    }
}
