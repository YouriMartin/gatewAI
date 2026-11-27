package io.github.yourimartin.gatewai.domain.model.calibration;

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
 * @param clientId             the API client this calibration applies to; null for
 *                             the global row every other client falls back to
 *                             (v4 D.3, ADR 0019). Only a cache calibration fitted
 *                             on that client's shadow labels has one.
 * @param source               where the labels came from
 * @param shadowFit            the judge, rubric and floor of a
 *                             {@link CalibrationSource#SHADOW} fit; null exactly
 *                             when the source is the labelled set
 * @param fitSummary           what the fit read and excluded, and the counts behind
 *                             it; its kind matches {@code source}. Null on a
 *                             calibration fitted before v4 D.3.
 */
public record ConformalCalibration(
    CalibrationTarget target,
    ConformalGuarantee guarantee,
    double alpha,
    double qhat,
    int sampleSize,
    String embeddingModel,
    String routingConfigVersion,
    Instant calibratedAt,
    String clientId,
    CalibrationSource source,
    ShadowFit shadowFit,
    FitSummary fitSummary
) {

  public ConformalCalibration {
    Objects.requireNonNull(target, "target is required");
    Objects.requireNonNull(guarantee, "guarantee is required");
    Objects.requireNonNull(source, "source is required");
    if (alpha <= 0 || alpha >= 1) {
      throw new IllegalArgumentException("alpha must be in (0,1), was " + alpha);
    }
    if (sampleSize <= 0) {
      throw new IllegalArgumentException("sampleSize must be positive");
    }
    if (clientId != null && clientId.isBlank()) {
      throw new IllegalArgumentException("a client calibration names its client");
    }
    // The rules of ADR 0019, which the table's CHECK constraints repeat (V16).
    if (target == CalibrationTarget.ROUTING
        && (clientId != null || source != CalibrationSource.LABELLED_SET)) {
      throw new IllegalArgumentException(
          "routing is calibrated globally, on the labelled set only");
    }
    if (clientId != null && source != CalibrationSource.SHADOW) {
      throw new IllegalArgumentException(
          "a client calibration is fitted on that client's shadow labels");
    }
    if ((source == CalibrationSource.SHADOW) != (shadowFit != null)) {
      throw new IllegalArgumentException(
          "a shadow calibration names its judge, rubric and floor; no other does");
    }
    if (fitSummary != null
        && (fitSummary instanceof FitSummary.Shadow) != (source == CalibrationSource.SHADOW)) {
      throw new IllegalArgumentException("the fit summary does not match the source");
    }
  }

  /** A global calibration fitted on the labelled set: every calibration before v4 D.3. */
  public ConformalCalibration(CalibrationTarget target, ConformalGuarantee guarantee,
                              double alpha, double qhat, int sampleSize,
                              String embeddingModel, String routingConfigVersion,
                              Instant calibratedAt) {
    this(target, guarantee, alpha, qhat, sampleSize, embeddingModel,
        routingConfigVersion, calibratedAt, null, CalibrationSource.LABELLED_SET, null,
        null);
  }

  /** Whether this is the row every client without its own falls back to. */
  public boolean isGlobal() {
    return clientId == null;
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
