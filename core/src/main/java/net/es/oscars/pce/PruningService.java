package net.es.oscars.pce;

import lombok.extern.slf4j.Slf4j;
import net.es.oscars.resv.ent.RequestedVlanFlowE;
import net.es.oscars.resv.ent.RequestedVlanJunctionE;
import net.es.oscars.resv.ent.RequestedVlanPipeE;
import net.es.oscars.topo.beans.TopoEdge;
import net.es.oscars.topo.beans.Topology;
import net.es.oscars.topo.ent.IntRangeE;
import net.es.oscars.topo.ent.ReservableBandwidthE;
import net.es.oscars.topo.ent.ReservableVlanE;
import net.es.oscars.topo.svc.TopoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@Component
public class PruningService {

    @Autowired
    private TopoService topoService;

    public Topology pruneForPipe(Topology topo, RequestedVlanPipeE pipe){
        Integer azBw = pipe.getAzMbps();
        Integer zaBw = pipe.getZaMbps();
        Set<String> vlans = new HashSet<>();
        vlans.addAll(getVlansFromJunction(pipe.getAJunction()));
        vlans.addAll(getVlansFromJunction(pipe.getZJunction()));
        return pruneTopology(topo, azBw, zaBw, vlans);
    }

    private Set<String> getVlansFromJunction(RequestedVlanJunctionE junction){
        return junction.getFixtures().stream().map(j -> j.getVlanExpression()).collect(Collectors.toSet());
    }

    public Topology pruneTopology(Topology topo, Integer azBw, Integer zaBw, Set<String> vlans){
        Topology pruned = new Topology();
        pruned.setLayer(topo.getLayer());
        pruned.setVertices(topo.getVertices());
        List<ReservableBandwidthE> bandwidths = topoService.reservableBandwidths();
        List<ReservableVlanE> resvVlans = topoService.reservableVlans();
        Set<TopoEdge> availableEdges = topo.getEdges().stream()
                .filter(e -> availableBW(e, azBw, zaBw, bandwidths))
                .filter(e -> availableVlans(e, vlans, resvVlans))
                .collect(Collectors.toSet());
        pruned.setEdges(availableEdges);
        return pruned;
    }

    private boolean availableVlans(TopoEdge edge, Set<String> vlans, List<ReservableVlanE> resvVlans) {
        String aUrn = edge.getA().getUrn();
        String zUrn = edge.getZ().getUrn();
        List<ReservableVlanE> aMatching = resvVlans.stream().filter(v -> v.getUrn().getUrn().equals(aUrn))
                .collect(Collectors.toList());
        List<ReservableVlanE> zMatching = resvVlans.stream().filter(v -> v.getUrn().getUrn().equals(zUrn))
                .collect(Collectors.toList());

        assert aMatching.size() <= 1 && zMatching.size() <= 1;
        if(aMatching.isEmpty() || zMatching.isEmpty()){
            return true;
        } else{
            for(String vlanString : vlans){
                Integer vlanInt = Integer.parseInt(vlanString);
                boolean aContainsVlan = aMatching.get(0).getVlanRanges().stream().map(IntRangeE::toDtoIntRange)
                        .anyMatch(vr -> vr.contains(vlanInt));
                boolean zContainsVlan = zMatching.get(0).getVlanRanges().stream().map(IntRangeE::toDtoIntRange)
                        .anyMatch(vr -> vr.contains(vlanInt));
                if(!aContainsVlan || !zContainsVlan){
                    return false;
                }
            }
            return true;
        }
    }

    private boolean availableBW(TopoEdge edge, Integer azBw, Integer zaBw, List<ReservableBandwidthE> bandwidths){
        String aUrn = edge.getA().getUrn();
        String zUrn = edge.getZ().getUrn();
        log.debug("checking if " + aUrn + " and " + zUrn + " have enough bandwidth ");
        List<ReservableBandwidthE> aMatching = bandwidths.stream().filter(bw -> bw.getUrn().getUrn().equals(aUrn))
                .collect(Collectors.toList());
        List<ReservableBandwidthE> zMatching = bandwidths.stream().filter(bw -> bw.getUrn().getUrn().equals(zUrn))
                .collect(Collectors.toList());

        assert aMatching.size() <= 1 && zMatching.size() <=1;
        if (aMatching.isEmpty() || zMatching.isEmpty()) {
            log.info("bandwidth does not apply to " + aUrn + " or " + zUrn);
            return true;
        } else {
            return aMatching.get(0).getEgressBw() >= azBw && aMatching.get(0).getIngressBw() >= zaBw
                    && zMatching.get(0).getIngressBw() >= azBw && zMatching.get(0).getEgressBw() >= zaBw;

        }
    }

}
