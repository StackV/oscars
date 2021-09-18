package net.es.oscars.sense.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class DeltaRequest {
    private String id;
    private String lastModified;
    private String modelId;
    private String addition;
    private String reduction;
    private String href;
}
