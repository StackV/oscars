package net.es.oscars.sense.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import javax.persistence.*;

@Table(name = "sensemodel")
@Entity
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SENSEModel {
    @JsonCreator
    public SENSEModel(@JsonProperty("uuid") @NonNull String uuid, @JsonProperty("model") String model,
            @JsonProperty("version") String version) {
        this.uuid = uuid;
        this.model = model;
        this.version = version;
    }

    @Id
    @GeneratedValue
    @JsonIgnore
    private Long id;

    @Column(unique = true)
    @NonNull
    private String uuid;

    private String model;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String version;

}
