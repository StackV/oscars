package net.es.oscars.sense.model;

import java.util.HashMap;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;
import net.es.oscars.web.simple.SimpleConnection;

/**
 * Encapsulates the list of connections and associated translations for a delta
 * request.
 */
@Data
@NoArgsConstructor
public class DeltaConnectionData {
    private String deltaId;
    private SimpleConnection conn;
    private final Map<String, DeltaTranslation> translations = new HashMap<>();
}
