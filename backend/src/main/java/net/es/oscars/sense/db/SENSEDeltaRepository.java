package net.es.oscars.sense.db;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import net.es.oscars.sense.model.entities.SENSEDelta;

import java.util.List;
import java.util.Optional;

@Repository
@Transactional
public interface SENSEDeltaRepository extends CrudRepository<SENSEDelta, Long> {

    List<SENSEDelta> findAll();

    Optional<SENSEDelta> findByUuid(String uuid);
}