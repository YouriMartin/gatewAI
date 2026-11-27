package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationTarget;

/**
 * The key of {@code conformal_calibration} since {@code V16} (v4 D.3): the target
 * and the client, {@link #GLOBAL} for the row every other client falls back to.
 * A primary-key column cannot be NULL, so the global row's client is the empty
 * string here and {@code null} in the domain.
 */
final class ConformalCalibrationId implements Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  /** The {@code client_id} of the global row. */
  static final String GLOBAL = "";

  private CalibrationTarget target;
  private String clientId;

  ConformalCalibrationId() {
    // JPA requires a no-arg constructor
  }

  ConformalCalibrationId(CalibrationTarget target, String clientId) {
    this.target = target;
    this.clientId = clientId == null ? GLOBAL : clientId;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ConformalCalibrationId that
        && target == that.target && Objects.equals(clientId, that.clientId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(target, clientId);
  }
}
