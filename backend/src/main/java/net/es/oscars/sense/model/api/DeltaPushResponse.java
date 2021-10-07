package net.es.oscars.sense.model.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import net.es.oscars.sense.model.DeltaModel;
import net.es.oscars.sense.model.DeltaState;

@Data
@AllArgsConstructor
public class DeltaPushResponse {
    private DeltaState state;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DeltaModel delta;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
}
