package io.github.yourimartin.gatewai.domain.model;

/**
 * One target's calibration and whether it is being applied (v2 batch 3).
 *
 * @param target        the decision this describes
 * @param status        whether {@code calibration} is in force
 * @param calibration   the stored calibration, null when never calibrated
 * @param fixedFallback the configured threshold that applies whenever
 *                      {@code status} is not {@link CalibrationStatus#VALID} —
 *                      carried alongside so a reader never has to guess what is
 *                      running instead
 */
public record CalibrationState(
    CalibrationTarget target,
    CalibrationStatus status,
    ConformalCalibration calibration,
    double fixedFallback
) {

  /** The threshold actually in force right now. */
  public double effectiveThreshold() {
    return status == CalibrationStatus.VALID
        ? calibration.similarityThreshold() : fixedFallback;
  }

  public boolean isApplied() {
    return status == CalibrationStatus.VALID;
  }
}
