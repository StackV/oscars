package net.es.oscars.pce;

import lombok.extern.slf4j.Slf4j;
import net.es.oscars.app.exc.PCEException;
import net.es.oscars.resv.ent.EroHop;
import net.es.oscars.resv.ent.VlanPipe;
import net.es.oscars.topo.beans.TopoAdjcy;
import net.es.oscars.topo.beans.TopoUrn;
import net.es.oscars.topo.svc.TopoService;
import net.es.oscars.web.beans.PcePath;
import net.es.oscars.web.beans.PceResponse;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AllDirectedPaths;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
public class AllPathsPCE {
    @Autowired
    private TopoService topoService;

    @Autowired
    private DijkstraPCE dijkstraPCE;

    public PceResponse calculatePaths(VlanPipe requestPipe,
                                      Map<String, Integer> availIngressBw,
                                      Map<String, Integer> availEgressBw) throws PCEException {


        Map<String, TopoUrn> baseline = topoService.getTopoUrnMap();

        List<TopoAdjcy> topoAdjcies = topoService.getTopoAdjcies();
        // two kinds of costing; one by hop count, one by metric

        Map<TopoAdjcy, Double> hopCosts = new HashMap<>();
        Map<TopoAdjcy, Double> metricCosts = new HashMap<>();

        for (TopoAdjcy adjcy : topoAdjcies) {
            hopCosts.put(adjcy, 1D);

            double cost = 0;
            for (Long metric : adjcy.getMetrics().values()) {
                if (metric > cost) {
                    cost = metric.doubleValue();
                }
            }
            metricCosts.put(adjcy, cost);
        }

        TopoUrn src = topoService.getTopoUrnMap().get(requestPipe.getA().getDeviceUrn());
        TopoUrn dst = topoService.getTopoUrnMap().get(requestPipe.getZ().getDeviceUrn());

        // first, get the shortest path (by metric)

        DirectedWeightedMultigraph<TopoUrn, TopoAdjcy> byMetricGraph = PceLibrary.makeGraph(topoAdjcies, metricCosts);
        DirectedWeightedMultigraph<TopoUrn, TopoAdjcy> byHopsGraph = PceLibrary.makeGraph(topoAdjcies, hopCosts);

        PcePath shortest = dijkstraPCE.shortestPath(byMetricGraph, src, dst);
        // first, get the shortest path (by metric)
        PcePath leastHops = dijkstraPCE.shortestPath(byHopsGraph, src, dst);
        PceLibrary.pathBandwidths(shortest, baseline, availIngressBw, availEgressBw);
        PceLibrary.pathBandwidths(leastHops, baseline, availIngressBw, availEgressBw);


        Integer shortestPathLength = shortest.getAzEro().size();

        // now we will look at all paths up to a limit of 15 edges longer than our shortest-by-metric path length
        // 15 is a bit arbitrary but will allow a good detour while not being too hard computationally
        Integer maxLength = shortestPathLength + 15;


        AllDirectedPaths<TopoUrn, TopoAdjcy> ap = new AllDirectedPaths<>(byMetricGraph);

        Instant ps = Instant.now();
        List<GraphPath<TopoUrn, TopoAdjcy>> paths = ap.getAllPaths(src, dst, true, maxLength);
        Instant pe = Instant.now();
        log.info(paths.size()+ " distinct paths found between "+src.getUrn() +
                " and "+ dst.getUrn()+ " found in time "+ Duration.between(ps, pe));


        PcePath widestAZ = null;
        PcePath widestSum = null;
        PcePath widestZA = null;
        PcePath fits = null;

        Instant es = Instant.now();
        for (GraphPath<TopoUrn, TopoAdjcy> path : paths) {

            List<EroHop> azEro = PceLibrary.toEro(path);
            List<EroHop> zaEro = new ArrayList<>();
            for (EroHop hop : azEro) {
                zaEro.add(EroHop.builder().urn(hop.getUrn()).build());
            }

            Collections.reverse(zaEro);

            PcePath pcePath = PcePath.builder()
                    .azEro(azEro)
                    .zaEro(zaEro)
                    .build();

            PceLibrary.pathBandwidths(pcePath, baseline, availIngressBw, availEgressBw);

            PceLibrary.pathCost(pcePath, path, metricCosts);

            // path that fits:
            if (pcePath.getAzAvailable() >= requestPipe.getAzBandwidth()
                    && pcePath.getZaAvailable() >= requestPipe.getZaBandwidth()) {
                // accept first path that matches; otherwise prefer the least-cost one
                if (fits == null) {
                    fits = pcePath;
                } else {
                    fits = preferredOf(fits, pcePath);
                }
            }

            // first path is best by default
            if (widestAZ == null) {
                widestAZ = pcePath;
                widestSum = pcePath;
                widestZA = pcePath;
            } else {

                // to get the widest paths, check for larger bandwidth.
                // if bandwidth is equal, then lowest cost; if equal, least hops.
                if (widestAZ.getAzAvailable() < pcePath.getAzAvailable()) {
                    widestAZ = pcePath;
                } else if (widestAZ.getAzAvailable().equals(pcePath.getAzAvailable())) {
                    widestAZ = preferredOf(widestAZ, pcePath);
                }
                if (widestZA.getZaAvailable() < pcePath.getZaAvailable()) {
                    widestZA = pcePath;
                } else if (widestZA.getZaAvailable().equals(pcePath.getZaAvailable())) {
                    widestZA = preferredOf(widestZA, pcePath);
                }

                Integer prevSum = widestSum.getAzAvailable() + widestSum.getZaAvailable();
                Integer newSum = pcePath.getAzAvailable() + pcePath.getZaAvailable();
                if (prevSum < newSum) {
                    widestSum  = pcePath;
                } else if (prevSum.equals(newSum)) {
                    widestSum = preferredOf(widestSum, pcePath);
                }
            }

        }
        Instant ee = Instant.now();
        log.info("widest paths found in time "+ Duration.between(es, ee));

        PceResponse response = PceResponse.builder()
                .widestAZ(widestAZ)
                .widestZA(widestZA)
                .widestSum(widestSum)
                .shortest(shortest)
                .leastHops(leastHops)
                .fits(fits)
                .evaluated(paths.size())
                .build();
        return response;
    }


    private PcePath preferredOf(PcePath a, PcePath b) {
        if (a.getCost() < b.getCost()) {
            return a;
        } else if (a.getCost() == b.getCost()) {
            if (a.getAzEro().size() < b.getAzEro().size()) {
                return a;
            }
        }
        return b;
    }

}
