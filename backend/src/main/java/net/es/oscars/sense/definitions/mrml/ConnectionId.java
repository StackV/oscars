package net.es.oscars.sense.definitions.mrml;

/**
 *
 * @author hacksaw
 */
public class ConnectionId {
  private static final String UUID_URN = "^urn:uuid:";

  public static String strip(String cid) {
    return cid.replaceFirst(UUID_URN, "");
  }
}
