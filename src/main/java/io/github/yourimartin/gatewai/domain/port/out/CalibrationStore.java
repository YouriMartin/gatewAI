package io.github.yourimartin.gatewai.domain.port.out;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;

/**
 * Stores the current calibration per target (v2 batch 3).
 *
 * <p>One row per target, overwritten on recalibration. No history: a decision
 * that needs explaining carries the {@code α} and the prediction set it was
 * taken under on its own row, so replaying it never depends on what the store
 * happens to hold today.
 */
public interface CalibrationStore {

  Optional<ConformalCalibration> find(CalibrationTarget target);

  List<ConformalCalibration> findAll();

  void save(ConformalCalibration calibration);
}
