// /*
// * SENSE Resource Manager (SENSE-RM) Copyright (c) 2016, The Regents
// * of the University of California, through Lawrence Berkeley National
// * Laboratory (subject to receipt of any required approvals from the
// * U.S. Dept. of Energy). All rights reserved.
// *
// * If you have questions about your rights to use or distribute this
// * software, please contact Berkeley Lab's Innovation & Partnerships
// * Office at IPO@lbl.gov.
// *
// * NOTICE. This Software was developed under funding from the
// * U.S. Department of Energy and the U.S. Government consequently retains
// * certain rights. As such, the U.S. Government has been granted for
// * itself and others acting on its behalf a paid-up, nonexclusive,
// * irrevocable, worldwide license in the Software to reproduce,
// * distribute copies to the public, prepare derivative works, and perform
// * publicly and display publicly, and to permit other to do so.
// *
// */
// package net.es.oscars.sense.definitions.mrml;

// import java.util.ArrayList;
// import java.util.List;
// import java.util.Optional;
// import net.es.nsi.dds.lib.jaxb.nml.NmlSwitchingServiceType;

// /**
// *
// * @author hacksaw
// */
// @Data
// public class NmlSwitchingSubnet {
// private String id;
// private String topologyId;
// private String connectionId;
// private long discovered;
// private String existsDuringId;
// private Optional<Long> startTime = Optional.empty();
// private Optional<Long> endTime = Optional.empty();
// private String tag;
// private NmlSwitchingServiceType switchingService;
// private List<NmlPort> ports = new ArrayList<>();

// public static String id(String topology, String localId) {
// return topology + ":switchingSubnet+" + localId;
// }
// }
