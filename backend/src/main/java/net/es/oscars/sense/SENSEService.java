package net.es.oscars.sense;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.Data;
import net.es.oscars.app.Startup;
import net.es.oscars.app.exc.StartupException;
import net.es.oscars.nsi.beans.NsiPeering;
import net.es.oscars.nsi.svc.NsiPopulator;
import net.es.oscars.nsi.svc.NsiService;
import net.es.oscars.resv.db.SENSERepository;
import net.es.oscars.resv.svc.ResvService;
import net.es.oscars.sense.definitions.Mrs;
import net.es.oscars.sense.definitions.Nml;
import net.es.oscars.sense.definitions.Sd;
import net.es.oscars.sense.model.SENSEModel;
import net.es.oscars.topo.svc.TopoService;
import net.es.oscars.web.beans.Interval;
import net.es.oscars.topo.beans.IntRange;
import net.es.oscars.topo.beans.ReservableCommandParam;
import net.es.oscars.topo.beans.Topology;
import net.es.oscars.topo.ent.Device;
import net.es.oscars.topo.ent.Layer3Ifce;
import net.es.oscars.topo.ent.Port;
import net.es.oscars.topo.ent.Version;
import net.es.oscars.topo.enums.Layer;

@Service
@Data
public class SENSEService {

    @Autowired
    private SENSERepository repository;

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

    public String buildModel(String topologyURI) throws StartupException, JsonProcessingException {
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
        Version v = topoService.getCurrent();
        DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_INSTANT;
        Instant now = Instant.now();

        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_MICRO_RULE_INF);
        // Manage root topology
        Resource resTopology = RdfOwl.createResource(model, topologyURI, Nml.Topology);
        model.add(model.createStatement(resTopology, Nml.version, Long.toString(v.getId())));
        model.add(model.createStatement(resTopology, Nml.name, topologyURI));

        Resource resLifetime = RdfOwl.createResource(model, resTopology.getURI() + ":lifetime", Nml.Lifetime);
        model.add(model.createStatement(resTopology, Nml.existsDuring, resLifetime));
        model.add(model.createStatement(resLifetime, Nml.start, dateFormatter.format(now)));

        // Manage switching service
        Resource resSwSvc = RdfOwl.createResource(model, resTopology.getURI() + ":l2switching", Nml.SwitchingService);
        model.add(model.createStatement(resSwSvc, Nml.encoding, RdfOwl.labelTypeVLAN));
        model.add(model.createStatement(resSwSvc, Nml.labelSwapping, "true"));
        model.add(model.createStatement(resTopology, Nml.hasService, resSwSvc));

        Resource resSwSvcDef = RdfOwl.createResource(model, resSwSvc.getURI() + ":sd+l2mpes", Sd.ServiceDefinition);
        model.add(model.createStatement(resSwSvcDef, Sd.serviceType, Sd.URI_SvcDef_L2MpEs));
        model.add(model.createStatement(resSwSvc, Sd.hasServiceDefinition, resSwSvcDef));
        //

        Topology topology = topoService.currentTopology();
        List<Port> edgePorts = new ArrayList<>();
        for (Device d : topology.getDevices().values()) {
            // System.out
            // .println("Iterating over device " + nsiService.nsiUrnFromInternal(d.getUrn())
            // + " : " + d.getId());
            for (Port p : d.getPorts()) {
                // System.out.println(
                // "Iterating over port " + nsiService.nsiUrnFromInternal(p.getUrn()) + " : " +
                // p.getId());
                // for (Layer3Ifce i : p.getIfces()) {
                // System.out.println("Iterating over interface " +
                // nsiService.nsiUrnFromInternal(i.getUrn()) + " : "
                // + i.getIpv4Address() + "|" + i.getIpv6Address());
                // }

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

        model.add(model.createStatement(resLifetime, Nml.end, dateFormatter.format(Instant.now())));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        model.write(baos, "TURTLE");
        return baos.toString();
    }

    public List<SENSEModel> pilotRetrieve() {
        return repository.findAll();
    }

    public void pilotAdd() {
        SENSEModel mock = new SENSEModel("testUUID", "<urn:test>...", "525kafj2ja");
        repository.save(mock);
    }

    public void pilotClear() {
        repository.deleteAll();
    }
}
