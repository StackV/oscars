/*
 * SENSE Resource Manager (SENSE-RM) Copyright (c) 2016, The Regents
 * of the University of California, through Lawrence Berkeley National
 * Laboratory (subject to receipt of any required approvals from the
 * U.S. Dept. of Energy).  All rights reserved.
 *
 * If you have questions about your rights to use or distribute this
 * software, please contact Berkeley Lab's Innovation & Partnerships
 * Office at IPO@lbl.gov.
 *
 * NOTICE.  This Software was developed under funding from the
 * U.S. Department of Energy and the U.S. Government consequently retains
 * certain rights. As such, the U.S. Government has been granted for
 * itself and others acting on its behalf a paid-up, nonexclusive,
 * irrevocable, worldwide license in the Software to reproduce,
 * distribute copies to the public, prepare derivative works, and perform
 * publicly and display publicly, and to permit other to do so.
 *
 */
package net.es.oscars.sense.definitions;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.google.common.base.Strings;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;

public class ExistsDuring {
  private final String id;
  private final Instant start;
  private final Instant end;

  public ExistsDuring(Resource service) {
    this.id = service.getURI();
    this.start = getTime(service, Nml.start);
    this.end = getTime(service, Nml.end);
  }

  public ExistsDuring(String id) {
    this.id = id;
    this.start = Instant.now();
    this.end = Instant.now().plus(365, ChronoUnit.DAYS);
  }

  public String getId() {
    return id;
  }

  public Instant getStart() {
    return start;
  }

  public Instant getEnd() {
    return end;
  }

  private Instant getTime(Resource service, Property start2) {
    Optional<Statement> s = Optional.ofNullable(service.getProperty(start2));
    if (s.isPresent() && !Strings.isNullOrEmpty(s.get().getString())) {

      LocalDateTime date = LocalDateTime.parse(s.get().getString());
      return date.atZone(ZoneId.systemDefault()).toInstant();
    }

    return null;
  }

  public static String id(String urn) {
    return urn + ":existsDuring";
  }
}
