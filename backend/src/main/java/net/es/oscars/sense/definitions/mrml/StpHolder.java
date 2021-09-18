package net.es.oscars.sense.definitions.mrml;

import net.es.oscars.sense.definitions.SimpleStp;

/**
 *
 * @author hacksaw
 */
public class StpHolder {
  private final String mrsPortId;
  private final SimpleStp stp;
  private final MrsBandwidthService bw;
  private final String nmlExistsDuringId;
  private final String mrsLabelId;

  public StpHolder(String mrsPortId, SimpleStp stp, MrsBandwidthService bw, String nmlExistsDuringId,
      String mrsLabelId) {
    this.mrsPortId = mrsPortId;
    this.stp = stp;
    this.bw = bw;
    this.nmlExistsDuringId = nmlExistsDuringId;
    this.mrsLabelId = mrsLabelId;
  }

  public String getMrsPortId() {
    return mrsPortId;
  }

  public SimpleStp getStp() {
    return stp;
  }

  public MrsBandwidthService getBw() {
    return bw;
  }

  public String getNmlExistsDuringId() {
    return nmlExistsDuringId;
  }

  public String getMrsLabelId() {
    return mrsLabelId;
  }
}
