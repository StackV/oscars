package net.es.oscars.sense.definitions.db;

import java.io.Serializable;
import java.util.concurrent.Semaphore;
import lombok.Synchronized;
import net.es.nsi.lib.soap.gen.nsi_2_0.framework.types.ServiceExceptionType;

/**
 * This class is used to track the status of an individual NSI operation,
 * providing a blocking semaphore allowing the delta request thread initiating
 * an NSI request to block on a result returned via an NSI ConnectionService
 * callback API thread.
 *
 * If an error is encountered within the NSI ConnectionService callback API
 * thread the state will be set to "failed" and the service exception will be
 * provided describing the error.
 *
 * @author hacksaw
 */
public class Operation implements Serializable {
  private final Semaphore completed = new Semaphore(0);
  private String correlationId;
  private OperationType operation;
  private StateType state;
  private ServiceExceptionType exception;

  // Hack around Safnari bug where gid and description not returned in
  Reservation reservation;

  public Semaphore getCompleted() {
    return completed;
  }

  /**
   * @return the operation
   */
  @Synchronized
  public OperationType getOperation() {
    return operation;
  }

  /**
   * @param operation the operation to set
   */
  @Synchronized
  public void setOperation(OperationType operation) {
    this.operation = operation;
  }

  /**
   * @return the correlationId
   */
  @Synchronized
  public String getCorrelationId() {
    return correlationId;
  }

  /**
   * @param correlationId the correlationId to set
   */
  @Synchronized
  public void setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
  }

  /**
   * @return the state
   */
  @Synchronized
  public StateType getState() {
    return state;
  }

  /**
   * @param state the state to set
   */
  @Synchronized
  public void setState(StateType state) {
    this.state = state;
  }

  /**
   * @return the exception
   */
  public ServiceExceptionType getException() {
    return exception;
  }

  /**
   * @param exception the exception to set
   */
  public void setException(ServiceExceptionType exception) {
    this.exception = exception;
  }

  /**
   * @return the exception
   */
  public Reservation getReservation() {
    return reservation;
  }

  /**
   * @param exception the exception to set
   */
  public void setReservation(Reservation reservation) {
    this.reservation = reservation;
  }
}
