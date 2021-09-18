package net.es.oscars.sense.db;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import net.es.oscars.sense.model.entities.SENSEModel;

import java.util.List;
import java.util.Optional;

@Repository
public interface SENSEModelRepository extends CrudRepository<SENSEModel, Long> {

    List<SENSEModel> findAll();

    Optional<SENSEModel> findById(String id);

    Optional<SENSEModel> findFirstByOrderByCreationTimeDesc();

    List<SENSEModel> findByHref(String href);

    List<SENSEModel> findByCreationTime(String creationTime);

}