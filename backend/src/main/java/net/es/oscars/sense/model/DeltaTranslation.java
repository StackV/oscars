package net.es.oscars.sense.model;

import lombok.Data;

@Data
public class DeltaTranslation {
    private static final String URN_SEP = ":";
    // private static final String LABEL_SEP = "+";

    public DeltaTranslation(String topoID, String urn) {
        this.urn = urn;

        String proc[] = urn.replace(topoID + URN_SEP, "").split(URN_SEP);
        this.junction = proc[0];
        if (proc.length > 1)
            this.port = proc[1].replace("_", "/");
    }

    private String urn;
    private String junction;
    private String port;

    public String toLabel() {
        return junction + URN_SEP + port;
    }
}
