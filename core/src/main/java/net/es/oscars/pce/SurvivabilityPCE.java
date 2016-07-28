package net.es.oscars.pce;

import lombok.extern.slf4j.Slf4j;
import net.es.oscars.dto.spec.SurvivabilityType;
import net.es.oscars.resv.ent.RequestedVlanPipeE;
import net.es.oscars.resv.ent.ReservedBandwidthE;
import net.es.oscars.resv.ent.ReservedVlanE;
import net.es.oscars.resv.ent.ScheduleSpecificationE;
import net.es.oscars.servicetopo.SurvivableServiceLayerTopology;
import net.es.oscars.topo.beans.TopoEdge;
import net.es.oscars.topo.beans.TopoVertex;
import net.es.oscars.topo.beans.Topology;
import net.es.oscars.topo.dao.UrnRepository;
import net.es.oscars.topo.ent.UrnE;
import net.es.oscars.topo.enums.Layer;
import net.es.oscars.topo.enums.VertexType;
import net.es.oscars.topo.svc.TopoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by jeremy on 7/27/16.
 */
@Slf4j
@Component
public class SurvivabilityPCE
{
    @Autowired
    private TopoService topoService;

    @Autowired
    private PruningService pruningService;

    @Autowired
    private UrnRepository urnRepo;

    @Autowired
    private SurvivableServiceLayerTopology serviceLayerTopology;

    @Autowired
    private DijkstraPCE dijkstraPCE;

    @Autowired
    private BhandariPCE bhandariPCE;

    /**
     * Depends on BhandariPCE to construct the survivable physical-layer EROs for a request after pruning the topology based on requested parameters
     *
     * @param requestPipe Requested pipe with required reservation parameters
     * @param requestSched Requested schedule parameters
     * @return A four- element Map containing both the primary and secondary link-disjoint forward-direction EROs and the primary and secondary link-disjoint reverse-direction EROs
     * @throws PCEException
     */
    public Map<String, List<TopoEdge>> computeSurvivableERO(RequestedVlanPipeE requestPipe, ScheduleSpecificationE requestSched, List<ReservedBandwidthE> rsvBwList, List<ReservedVlanE> rsvVlanList) throws PCEException
    {
        if(requestPipe.getEroSurvivability().equals(SurvivabilityType.SURVIVABILITY_TOTAL))
        {
            return computeSurvivableEroComplete(requestPipe, requestSched, rsvBwList, rsvVlanList);
        }
        else if(requestPipe.getEroSurvivability().equals(SurvivabilityType.SURVIVABILITY_PARTIAL))
        {
            return computeSurvivableEroPartial(requestPipe, requestSched, rsvBwList, rsvVlanList);
        }
        else
        {
            throw new PCEException("Unsupported SurvivabilityType");
        }
    }


    private Map<String, List<TopoEdge>> computeSurvivableEroComplete(RequestedVlanPipeE requestPipe, ScheduleSpecificationE requestSched, List<ReservedBandwidthE> rsvBwList, List<ReservedVlanE> rsvVlanList) throws PCEException
    {
        UrnE srcDeviceURN = requestPipe.getAJunction().getDeviceUrn();
        UrnE dstDeviceURN = requestPipe.getZJunction().getDeviceUrn();
        UrnE srcPortURN = requestPipe.getAJunction().getFixtures().iterator().next().getPortUrn();
        UrnE dstPortURN = requestPipe.getZJunction().getFixtures().iterator().next().getPortUrn();

        VertexType srcType = topoService.getVertexTypeFromDeviceType(srcDeviceURN.getDeviceType());
        VertexType dstType = topoService.getVertexTypeFromDeviceType(dstDeviceURN.getDeviceType());

        TopoVertex srcDevice = new TopoVertex(srcDeviceURN.getUrn(), srcType);
        TopoVertex dstDevice = new TopoVertex(dstDeviceURN.getUrn(), dstType);
        TopoVertex srcPort = new TopoVertex(srcPortURN.getUrn(), VertexType.PORT);
        TopoVertex dstPort = new TopoVertex(dstPortURN.getUrn(), VertexType.PORT);

        Topology multiLayerTopo = topoService.getMultilayerTopology();

        // Identify src/dst ports for disjoint routing

        Set<TopoEdge> fixtureEdges = multiLayerTopo.getEdges().stream()
                .filter(e -> e.getA().equals(srcPort) || e.getA().equals(dstPort) || e.getZ().equals(srcPort) || e.getZ().equals(dstPort))
                .collect(Collectors.toSet());


        // Bandwidth and Vlan pruning
        Topology prunedTopo = pruningService.pruneWithPipeAZ(multiLayerTopo, requestPipe, requestSched, rsvBwList, rsvVlanList);

        // Disjoint shortest-path routing
        List<List<TopoEdge>> azPathPairCalculated = bhandariPCE.computePathPair(prunedTopo, srcDevice, dstDevice);

        if(azPathPairCalculated.isEmpty())
        {
            throw new PCEException("Empty path-pair in Survivability PCE");
        }
        else if(azPathPairCalculated.size() != 2)
        {
            throw new PCEException("Secondary path could not be found in Survivability PCE");
        }

        // Add the src/dst ports back to the routes to complete the EROs
        for(TopoEdge fixEdge : fixtureEdges)
        {
            if(fixEdge.getA().equals(srcPort) && fixEdge.getZ().equals(srcDevice))
            {
                for(List<TopoEdge> azERO : azPathPairCalculated)
                {
                    azERO.add(0, fixEdge);
                }
            }
            else if(fixEdge.getA().equals(dstDevice) && fixEdge.getZ().equals(dstPort))
            {
                for(List<TopoEdge> azERO : azPathPairCalculated)
                {
                    azERO.add(fixEdge);
                }
            }
        }

        // Get palindromic paths in reverse-direction //
        List<List<TopoEdge>> zaPathPairCalculated = new ArrayList<>();

        // 1. Reverse the links
        for(List<TopoEdge> azERO : azPathPairCalculated)
        {
            List<TopoEdge> zaERO = new ArrayList<>();

            for(TopoEdge azEdge : azERO)
            {
                Optional<TopoEdge> reverseEdge = prunedTopo.getEdges().stream()
                        .filter(r -> r.getA().equals(azEdge.getZ()))
                        .filter(r -> r.getZ().equals(azEdge.getA()))
                        .findFirst();

                if(reverseEdge.isPresent())
                    zaERO.add(reverseEdge.get());
            }

            zaPathPairCalculated.add(zaERO);
        }

        // 2. Reverse the order
        for(List<TopoEdge> zaERO : zaPathPairCalculated)
        {
            Collections.reverse(zaERO);
        }

        assert(azPathPairCalculated.size() == 2);
        assert(azPathPairCalculated.size() == zaPathPairCalculated.size());


        for(int p = 0; p < azPathPairCalculated.size(); p++)
        {
            List<TopoEdge> azERO = azPathPairCalculated.get(p);
            List<TopoEdge> zaERO = zaPathPairCalculated.get(p);
            log.info("AZ: " + azERO.stream().map(e -> "(" + e.getA().getUrn() + ", " + e.getZ().getUrn() + ")").collect(Collectors.toList()).toString());
            log.info("ZA: " + zaERO.stream().map(e -> "(" + e.getA().getUrn() + ", " + e.getZ().getUrn() + ")").collect(Collectors.toList()).toString());
            assert(azERO.size() == zaERO.size());
        }

        Map<String, List<TopoEdge>> theMap = new HashMap<>();
        theMap.put("azPrimary", azPathPairCalculated.get(0));
        theMap.put("zaPrimary", zaPathPairCalculated.get(0));
        theMap.put("azSecondary", azPathPairCalculated.get(1));
        theMap.put("zaSecondary", zaPathPairCalculated.get(1));

        log.info("AZ Primary: " + azPathPairCalculated.get(0).toString());
        log.info("ZA Primary: " + zaPathPairCalculated.get(0).toString());
        log.info("AZ Secondary: " + azPathPairCalculated.get(1).toString());
        log.info("ZA Secondary: " + zaPathPairCalculated.get(1).toString());

        return theMap;
    }


    private Map<String, List<TopoEdge>> computeSurvivableEroPartial(RequestedVlanPipeE requestPipe, ScheduleSpecificationE requestSched, List<ReservedBandwidthE> rsvBwList, List<ReservedVlanE> rsvVlanList) throws PCEException
    {
        Topology ethTopo = topoService.layer(Layer.ETHERNET);
        Topology intTopo = topoService.layer(Layer.INTERNAL);
        Topology mplsTopo = topoService.layer(Layer.MPLS);

        Topology physTopo = topoService.getMultilayerTopology();

        // Filter MPLS-ports and MPLS-devices out of ethTopo
        Set<TopoVertex> portsOnly = ethTopo.getVertices().stream()
                .filter(v -> v.getVertexType().equals(VertexType.PORT))
                .collect(Collectors.toSet());

        for(TopoEdge intEdge : intTopo.getEdges())
        {
            TopoVertex vertA = intEdge.getA();
            TopoVertex vertZ = intEdge.getZ();

            if(portsOnly.isEmpty())
            {
                break;
            }

            if(portsOnly.contains(vertA))
            {
                if(!vertZ.getVertexType().equals(VertexType.ROUTER))
                {
                    portsOnly.remove(vertA);
                }
            }
        }

        ethTopo.getVertices().removeIf(v -> v.getVertexType().equals(VertexType.ROUTER));
        ethTopo.getVertices().removeAll(portsOnly);

        // Filter Devices and Ports out of intTopo
        intTopo.getVertices().removeAll(intTopo.getVertices());

        // Initialize Service-Layer Topology
        serviceLayerTopology.setTopology(ethTopo);
        serviceLayerTopology.setTopology(intTopo);
        serviceLayerTopology.setTopology(mplsTopo);
        serviceLayerTopology.createMultilayerTopology();
        serviceLayerTopology.resetLogicalLinks();

        UrnE srcDeviceURN = requestPipe.getAJunction().getDeviceUrn();
        UrnE dstDeviceURN = requestPipe.getZJunction().getDeviceUrn();

        VertexType srcType = topoService.getVertexTypeFromDeviceType(srcDeviceURN.getDeviceType());
        VertexType dstType = topoService.getVertexTypeFromDeviceType(dstDeviceURN.getDeviceType());

        TopoVertex srcDevice = new TopoVertex(srcDeviceURN.getUrn(), srcType);
        TopoVertex dstDevice = new TopoVertex(dstDeviceURN.getUrn(), dstType);

        UrnE srcPortURN = requestPipe.getAJunction().getFixtures().iterator().next().getPortUrn();
        UrnE dstPortURN = requestPipe.getZJunction().getFixtures().iterator().next().getPortUrn();

        TopoVertex srcPort = new TopoVertex(srcPortURN.getUrn(), VertexType.PORT);
        TopoVertex dstPort = new TopoVertex(dstPortURN.getUrn(), VertexType.PORT);

        // Handle MPLS-layer source/destination devices
        serviceLayerTopology.buildLogicalLayerSrcNodes(srcDevice, srcPort);
        serviceLayerTopology.buildLogicalLayerDstNodes(dstDevice, dstPort);

        // Performs shortest path routing on MPLS-layer to properly assign weights to each logical link on Service-Layer
        serviceLayerTopology.calculateLogicalLinkWeights(requestPipe, requestSched, urnRepo.findAll(), rsvBwList, rsvVlanList);

        Topology slTopo;

        slTopo = serviceLayerTopology.getSLTopology();

        Topology prunedSlTopo = pruningService.pruneWithPipe(slTopo, requestPipe, requestSched, rsvBwList, rsvVlanList);
        Topology prunedPhysicalTopo = pruningService.pruneWithPipe(physTopo, requestPipe, requestSched, rsvBwList, rsvVlanList);

        TopoVertex serviceLayerSrcNode;
        TopoVertex serviceLayerDstNode;

        if(srcDevice.getVertexType().equals(VertexType.SWITCH))
        {
            serviceLayerSrcNode = srcPort;
        }
        else
        {
            serviceLayerSrcNode = serviceLayerTopology.getVirtualNode(srcDevice);
            assert(serviceLayerSrcNode != null);
        }

        if(dstDevice.getVertexType().equals(VertexType.SWITCH))
        {
            serviceLayerDstNode = dstPort;
        }
        else
        {
            serviceLayerDstNode = serviceLayerTopology.getVirtualNode(dstDevice);
            assert(serviceLayerDstNode != null);
        }

        // Shortest path routing on Service-Layer
        List<TopoEdge> azServiceLayerERO = dijkstraPCE.computeShortestPathEdges(prunedSlTopo, serviceLayerSrcNode, serviceLayerDstNode);

        if (azServiceLayerERO.isEmpty())
        {
            throw new PCEException("Empty path NonPalindromic PCE");
        }

        // Get palindromic Service-Layer path in reverse-direction
        List<TopoEdge> zaServiceLayerERO = new LinkedList<>();

        // 1. Reverse the links
        for(TopoEdge azEdge : azServiceLayerERO)
        {
            Optional<TopoEdge> reverseEdge = prunedSlTopo.getEdges().stream()
                    .filter(r -> r.getA().equals(azEdge.getZ()))
                    .filter(r -> r.getZ().equals(azEdge.getA()))
                    .findFirst();

            if(reverseEdge.isPresent())
            {
                zaServiceLayerERO.add(reverseEdge.get());
            }
        }

        // 2. Reverse the order
        Collections.reverse(zaServiceLayerERO);

        Map<String, List<TopoEdge>> theMap = new HashMap<>();

        if(!(azServiceLayerERO.size() == zaServiceLayerERO.size()))
            return  theMap;

        // Obtain physical ERO from Service-Layer EROs
        List<TopoEdge> azEROPrimary = serviceLayerTopology.getActualPrimaryERO(azServiceLayerERO);
        List<TopoEdge> azEROSecondary = serviceLayerTopology.getActualSecondaryERO(azServiceLayerERO);

        // Get palindromic Physical-Layer path in reverse-direction
        List<TopoEdge> zaEROPrimary = new LinkedList<>();
        List<TopoEdge> zaEROSecondary = new LinkedList<>();

        // 1. Reverse the links
        for(TopoEdge azEdge : azEROPrimary)
        {
            Optional<TopoEdge> reverseEdge = prunedPhysicalTopo.getEdges().stream()
                    .filter(r -> r.getA().equals(azEdge.getZ()))
                    .filter(r -> r.getZ().equals(azEdge.getA()))
                    .findFirst();

            if(reverseEdge.isPresent())
            {
                zaEROPrimary.add(reverseEdge.get());
            }
        }

        for(TopoEdge azEdge : azEROSecondary)
        {
            Optional<TopoEdge> reverseEdge = prunedPhysicalTopo.getEdges().stream()
                    .filter(r -> r.getA().equals(azEdge.getZ()))
                    .filter(r -> r.getZ().equals(azEdge.getA()))
                    .findFirst();

            if(reverseEdge.isPresent())
            {
                zaEROSecondary.add(reverseEdge.get());
            }
        }

        // 2. Reverse the order
        Collections.reverse(zaEROPrimary);
        Collections.reverse(zaEROSecondary);

        if(!(azEROPrimary.size() == zaEROPrimary.size()))
            return  theMap;
        if(!(azEROSecondary.size() == zaEROSecondary.size()))
            return  theMap;

        theMap.put("azPrimary", azEROPrimary);
        theMap.put("zaPrimary", zaEROPrimary);
        theMap.put("azSecondary", azEROSecondary);
        theMap.put("zaSecondary", zaEROSecondary);

        log.info("AZ Primary: " + azEROPrimary.toString());
        log.info("ZA Primary: " + zaEROPrimary.toString());
        log.info("AZ Secondary: " + azEROSecondary.toString());
        log.info("ZA Secondary: " + zaEROSecondary.toString());

        return theMap;
    }
}
