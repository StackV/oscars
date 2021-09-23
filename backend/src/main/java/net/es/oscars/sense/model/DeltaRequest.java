package net.es.oscars.sense.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DeltaRequest {
    private String id;
    private String lastModified;
    private String modelId;
    private String addition;
    private String reduction;
    private String href;
}
