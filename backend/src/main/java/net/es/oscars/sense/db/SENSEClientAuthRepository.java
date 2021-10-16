package net.es.oscars.sense.db;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import net.es.oscars.sense.model.entities.SENSEClientAuth;

import java.util.List;
import java.util.Optional;

// @Repository
public interface SENSEClientAuthRepository // extends CrudRepository<SENSEClientAuth, Long>
{

    List<SENSEClientAuth> findAll();

    Optional<SENSEClientAuth> findByCn(String cn);
}