package net.es.oscars.sense.definitions.mrml;

import net.es.oscars.sense.definitions.Nml;
import net.es.oscars.sense.definitions.SimpleLabel;

import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;

/**
 *
 * @author hacksaw
 */
public class NmlLabel {

  private final Resource label;
  private final Resource labelType;
  private final Statement labelValue;

  public NmlLabel(Resource label) {
    this.label = label;

    labelType = label.getProperty(Nml.labeltype).getResource();
    labelValue = label.getProperty(Nml.value);
  }

  public String getId() {
    return label.getURI();
  }

  /**
   * @return the labelType
   */
  public Resource getLabelType() {
    return labelType;
  }

  /**
   * @return the labelValue
   */
  public Statement getLabelValue() {
    return labelValue;
  }

  public SimpleLabel getSimpleLabel() {
    SimpleLabel simpleLabel = new SimpleLabel(SimpleLabel.strip(labelType.getURI()), labelValue.getString());
    return simpleLabel;
  }

}
