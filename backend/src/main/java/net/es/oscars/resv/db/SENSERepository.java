package net.es.oscars.resv.db;

import net.es.oscars.sense.model.SENSEModel;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SENSERepository extends CrudRepository<SENSEModel, Long> {

    List<SENSEModel> findAll();

    Optional<SENSEModel> findById(String id);

    List<SENSEModel> findByHref(String href);

    List<SENSEModel> findByCreationTime(String creationTime);

}