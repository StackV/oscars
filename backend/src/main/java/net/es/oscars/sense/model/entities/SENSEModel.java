package net.es.oscars.sense.model.entities;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Table(name = "sensemodel")
@Entity
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SENSEModel implements Serializable {
    @Id
    private String id;

    @Column(nullable = false)
    private String creationTime;

    @Column(nullable = false)
    private String model;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String href;

}
