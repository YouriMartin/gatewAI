package io.github.yourimartin.gatewai.domain.port.out;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalCalibration;

/**
 * Stores the current calibration per target and scope (v2 batch 3; per client
 * since v4 D.3, ADR 0019).
 *
 * <p>One row per (target, client), the client null for the global row,
 * overwritten on recalibration. No history: a decision that needs explaining
 * carries the {@code α} and the prediction set it was taken under on its own row,
 * so replaying it never depends on what the store happens to hold today.
 */
public interface CalibrationStore {

  /**
   * The calibration of one scope.
   *
   * @param clientId the client, or null for the global row
   */
  Optional<ConformalCalibration> find(CalibrationTarget target, String clientId);

  /** Every stored calibration, global and per client. */
  List<ConformalCalibration> findAll();

  /** Stores {@code calibration}, replacing the one of the same target and scope. */
  void save(ConformalCalibration calibration);

  /**
   * Removes a client's own calibration, returning that client to the global one.
   *
   * @return whether there was one to remove
   */
  boolean delete(CalibrationTarget target, String clientId);
}
