package io.github.yourimartin.gatewai;

import java.time.Instant;
import java.util.List;
import java.util.function.DoubleSupplier;

import io.github.yourimartin.gatewai.domain.model.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.CalibrationStatus;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.ConformalGuarantee;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;

/**
 * Ready-made {@link CalibrationUseCase} stubs for tests (v2 batch 3).
 *
 * <p>Shared because three adapters now ask which threshold is in force, and each
 * of them has to be tested twice: calibrated, and degraded to the fixed value.
 */
public final class CalibrationFixtures {

  private CalibrationFixtures() {
  }

  /** Nothing calibrated: every target falls back to {@code fixedFallback}. */
  public static CalibrationUseCase none(double fixedFallback) {
    return none(() -> fixedFallback);
  }

  /**
   * Nothing calibrated, and the fixed fallback is read on every call.
   *
   * <p>Needed because the configured threshold is hot-tunable: the real service
   * re-reads {@code RoutingConfigPort} each time, so a stub that snapshotted the
   * value at construction would quietly ignore a test changing it afterwards.
   */
  public static CalibrationUseCase none(DoubleSupplier fixedFallback) {
    return new Stub(null, CalibrationStatus.ABSENT, fixedFallback);
  }

  /** A calibration that exists but no longer describes the running system. */
  public static CalibrationUseCase stale(ConformalCalibration calibration,
                                         double fixedFallback) {
    return new Stub(calibration, CalibrationStatus.STALE, () -> fixedFallback);
  }

  /** A calibration in force: its threshold is the one applied. */
  public static CalibrationUseCase applied(ConformalCalibration calibration,
                                           double fixedFallback) {
    return new Stub(calibration, CalibrationStatus.VALID, () -> fixedFallback);
  }

  /**
   * A calibration whose {@link ConformalCalibration#similarityThreshold()} is
   * {@code threshold}, expressed through the guarantee that target really uses.
   */
  public static ConformalCalibration calibration(CalibrationTarget target,
                                                 double threshold) {
    return switch (target) {
      case CACHE -> new ConformalCalibration(target,
          ConformalGuarantee.WRONG_ANSWER_RATE, 0.05, threshold, 200,
          "nomic-embed-text", null, Instant.parse("2026-08-11T00:00:00Z"));
      case ROUTING -> new ConformalCalibration(target,
          ConformalGuarantee.CORRECT_TARGET_COVERAGE, 0.10, 1 - threshold, 200,
          "nomic-embed-text", "config-v1", Instant.parse("2026-08-11T00:00:00Z"));
    };
  }

  private record Stub(ConformalCalibration calibration, CalibrationStatus status,
                      DoubleSupplier fixedFallback) implements CalibrationUseCase {

    @Override
    public CalibrationState state(CalibrationTarget target) {
      boolean matches = calibration != null && calibration.target() == target;
      return new CalibrationState(target,
          matches ? status : CalibrationStatus.ABSENT,
          matches ? calibration : null,
          fixedFallback.getAsDouble());
    }

    @Override
    public List<CalibrationState> states() {
      return List.of(state(CalibrationTarget.CACHE), state(CalibrationTarget.ROUTING));
    }

    @Override
    public List<CalibrationState> recalibrate(Double routingAlpha, Double cacheAlpha) {
      throw new UnsupportedOperationException("not a calibrating stub");
    }
  }
}
