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
package net.es.oscars.sense.definitions.mrml;

import lombok.extern.slf4j.Slf4j;
import net.es.oscars.sense.definitions.Mrs;
import net.es.oscars.sense.definitions.Nml;
import net.es.oscars.sense.tools.ModelUtil;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;

/**
 *
 * @author hacksaw
 */
@Slf4j
public class MrsBandwidthService {
  private final String id;
  private MrsUnits unit = MrsUnits.bps;
  private long maximumCapacity = 0;
  private MrsBandwidthType bandwidthType = MrsBandwidthType.bestEffort;

  /**
   *
   * @param port
   * @param model
   * @throws IllegalArgumentException
   */
  public MrsBandwidthService(Resource port, Model model) {
    // Get the MrsBandwidthService assocated with this BidirectionalPort.
    Statement bwServiceRef = port.getProperty(Nml.hasService);

    // No BandwidthService indicates "bestEffort" service.
    if (bwServiceRef == null) {
      log.info("[MrsBandwidthService] port does not contain a hasService property {}", port.getURI());
      this.id = port.getURI() + ":BandwidthService";
      return;
    }

    Resource service = ModelUtil.getResourceOfType(model, bwServiceRef.getResource(), Mrs.BandwidthService);
    if (service == null) {
      log.info("[MrsBandwidthService] BidirectionalPort does not contain BandwidthService {}", port.getURI());
      this.id = port.getURI() + ":BandwidthService";
      return;
    }

    this.id = service.getURI();

    Statement typeProperty = service.getProperty(Mrs.type);
    if (typeProperty == null) {
      log.info("[MrsBandwidthService] BandwidthService does not contain a type property {}", service.getURI());
    } else {
      bandwidthType = MrsBandwidthType.valueOf(typeProperty.getString());
    }

    Statement unitProperty = service.getProperty(Mrs.unit);
    if (unitProperty == null) {
      log.info("[MrsBandwidthService] BandwidthService does not contain a unit property {}", service.getURI());
    } else {
      unit = MrsUnits.valueOf(unitProperty.getString().toLowerCase());
    }

    Statement mcProperty = service.getProperty(Mrs.maximumCapacity);
    if (mcProperty == null) {
      log.info("BandwidthService does not contain a maximumCapacity property {}", service.getURI());
    } else {
      maximumCapacity = mcProperty.getLong();
    }
  }

  /**
   *
   * @return
   */
  public String getId() {
    return id;
  }

  /**
   *
   * @return
   */
  public MrsUnits getUnit() {
    return unit;
  }

  /**
   *
   * @return
   */
  public long getMaximumCapacity() {
    return maximumCapacity;
  }

  /**
   * @return the bandwidthType
   */
  public MrsBandwidthType getBandwidthType() {
    return bandwidthType;
  }
}
