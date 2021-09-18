package net.es.oscars.sense.definitions.mrml;

import java.util.HashMap;

import lombok.Data;
import net.es.nsi.lib.soap.gen.nsi_2_0.connection.types.ReserveType;

/**
 *
 * @author hacksaw
 */
@Data
public class ReserveHolder {
  private ReserveType reserve;
  private String switchingSubnetId;
  private HashMap<String, StpHolder> ports = new HashMap<>();

}
