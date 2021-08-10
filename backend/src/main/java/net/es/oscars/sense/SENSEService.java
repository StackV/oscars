package net.es.oscars.sense;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

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
import net.es.oscars.nsi.svc.NsiPopulator;
import net.es.oscars.nsi.svc.NsiService;
import net.es.oscars.resv.svc.ResvService;
import net.es.oscars.sense.definitions.Nml;
import net.es.oscars.topo.svc.TopoService;
import net.es.oscars.topo.beans.Topology;
import net.es.oscars.topo.ent.Device;
import net.es.oscars.topo.ent.Port;
import net.es.oscars.topo.ent.Version;
import net.es.oscars.topo.enums.Layer;

@Service
@Data
public class SENSEService {

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

    private static String nsBase = "http://schemas.ogf.org/nml/2013/05/base#";
    private static String nsDefs = "http://schemas.ogf.org/nsi/2013/12/services/definition";
    private static String nsEth = "http://schemas.ogf.org/nml/2012/10/ethernet";
    private static String isAliasType = "http://schemas.ogf.org/nml/2013/05/base#isAlias";

    private static String nsDiscovery = "http://schemas.ogf.org/nsi/2014/02/discovery/nsa";
    private static String nsVcard = "urn:ietf:params:xml:ns:vcard-4.0";

    public String buildModel() throws StartupException, JsonProcessingException {
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

        String topologyURI = "URI:MOCK";

        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_MICRO_RULE_INF);
        Resource resTopology = RdfOwl.createResource(model, topologyURI, Nml.Topology);
        Resource resSwSvc = RdfOwl.createResource(model, resTopology.getURI() + ":l2switching", Nml.SwitchingService);
        model.add(model.createStatement(resSwSvc, Nml.encoding, RdfOwl.labelTypeVLAN));
        model.add(model.createStatement(resSwSvc, Nml.labelSwapping, "true"));
        model.add(model.createStatement(resTopology, Nml.hasService, resSwSvc));

        Topology topology = topoService.currentTopology();
        List<Port> edgePorts = new ArrayList<>();
        for (Device d : topology.getDevices().values()) {
            System.out.println("Iterating over device " + d.getUrn());
            for (Port p : d.getPorts()) {
                String nsiUrn = nsiService.nsiUrnFromInternal(p.getUrn());
                if (p.getCapabilities().contains(Layer.ETHERNET) && !p.getReservableVlans().isEmpty()) {
                    boolean allow = false;
                    for (String filter : nsiPopulator.getFilter()) {
                        if (p.getUrn().contains(filter)) {
                            allow = true;
                        }

                    }
                    if (allow) {
                        System.out.println("== Adding port " + nsiUrn);
                        edgePorts.add(p);
                    }
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        model.write(baos, "TURTLE");
        return baos.toString();
    }
}
