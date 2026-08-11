package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A calibrated decision threshold, with what it promises and what it was
 * measured against (v2 batch 3).
 *
 * <p>Replaces a guessed constant with a number computed from labelled data, and
 * carries enough provenance to know when it stops being true. A calibration is
 * only meaningful next to the embedding model that produced its similarities
 * and — for routing — the route definitions those similarities were measured
 * against; both are stamped here so a changed system degrades to the fixed
 * threshold instead of silently applying a number from another world.
 *
 * @param target               which decision this governs
 * @param guarantee            what {@code alpha} promises
 * @param alpha                the risk level the guarantee is stated at
 * @param qhat                 the conformal quantile of the calibration scores
 * @param sampleSize           how many labelled cases it was computed from
 * @param embeddingModel       the model that produced those similarities
 * @param routingConfigVersion the routing rules in force at calibration time;
 *                             null for {@link CalibrationTarget#CACHE}, which
 *                             does not depend on them
 * @param calibratedAt         when it was computed
 */
public record ConformalCalibration(
    CalibrationTarget target,
    ConformalGuarantee guarantee,
    double alpha,
    double qhat,
    int sampleSize,
    String embeddingModel,
    String routingConfigVersion,
    Instant calibratedAt
) {

  public ConformalCalibration {
    Objects.requireNonNull(target, "target is required");
    Objects.requireNonNull(guarantee, "guarantee is required");
    if (alpha <= 0 || alpha >= 1) {
      throw new IllegalArgumentException("alpha must be in (0,1), was " + alpha);
    }
    if (sampleSize <= 0) {
      throw new IllegalArgumentException("sampleSize must be positive");
    }
  }

  /**
   * The similarity a candidate must reach to enter the prediction set.
   *
   * <p>The two guarantees measure non-conformity from opposite ends, so they
   * map to a threshold differently: coverage scores {@code 1 − similarity} on
   * the correct target and accepts everything above {@code 1 − q̂}; the
   * wrong-answer rate scores the similarity of pairs that must not be served
   * and accepts only what beats {@code q̂}, which is where those pairs stop.
   */
  public double similarityThreshold() {
    return switch (guarantee) {
      case CORRECT_TARGET_COVERAGE -> 1 - qhat;
      case WRONG_ANSWER_RATE -> qhat;
    };
  }

  /** Whether {@code similarity} is inside the prediction set. */
  public boolean admits(double similarity) {
    return similarity >= similarityThreshold();
  }

  /**
   * Whether this calibration still describes the running system.
   *
   * <p>A cache calibration survives a routing-rule edit: it measures the
   * similarity between two request texts, which routes have no part in. A
   * routing calibration does not — changing a route's examples changes the very
   * similarities it was fitted on.
   */
  public CalibrationStatus statusFor(String currentEmbeddingModel,
                                     String currentRoutingConfigVersion) {
    if (!Objects.equals(embeddingModel, currentEmbeddingModel)) {
      return CalibrationStatus.STALE;
    }
    return switch (target) {
      case CACHE -> CalibrationStatus.VALID;
      case ROUTING -> Objects.equals(routingConfigVersion, currentRoutingConfigVersion)
          ? CalibrationStatus.VALID : CalibrationStatus.STALE;
    };
  }
}
