package net.es.oscars.sense.model.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import net.es.oscars.sense.model.DeltaState;
import net.es.oscars.sense.model.entities.SENSEDelta;

@Data
@AllArgsConstructor
public class DeltaCommitResponse {
    private DeltaState state;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SENSEDelta delta;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
}
