package io.github.yourimartin.gatewai.adapter.in.web;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.model.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;

/**
 * One target's calibration as the admin API reports it (v2 batch 3).
 *
 * <p>{@code effectiveThreshold} and {@code applied} come first on purpose: the
 * question an operator actually has is "what is running right now?", and the
 * answer must not require reading {@code qHat} and inferring it.
 *
 * @param target             {@code CACHE} or {@code ROUTING}
 * @param status             VALID, STALE, ABSENT or DISABLED
 * @param applied            whether the calibrated threshold is the one in force
 * @param effectiveThreshold the similarity threshold actually being applied
 * @param fixedFallback      the configured threshold used when it is not
 * @param guarantee          what {@code alpha} promises
 * @param alpha              the risk level
 * @param qhat               the conformal quantile
 * @param sampleSize         labelled cases behind it
 * @param embeddingModel     the model it was fitted with
 * @param routingConfigVersion the routing rules it was fitted against
 * @param calibratedAt       when
 */
record CalibrationView(
    String target,
    String status,
    boolean applied,
    double effectiveThreshold,
    double fixedFallback,
    String guarantee,
    Double alpha,
    Double qhat,
    Integer sampleSize,
    String embeddingModel,
    String routingConfigVersion,
    Instant calibratedAt
) {

  static CalibrationView of(CalibrationState state) {
    ConformalCalibration calibration = state.calibration();
    return new CalibrationView(
        state.target().name(),
        state.status().name(),
        state.isApplied(),
        state.effectiveThreshold(),
        state.fixedFallback(),
        calibration == null ? null : calibration.guarantee().name(),
        calibration == null ? null : calibration.alpha(),
        calibration == null ? null : calibration.qhat(),
        calibration == null ? null : calibration.sampleSize(),
        calibration == null ? null : calibration.embeddingModel(),
        calibration == null ? null : calibration.routingConfigVersion(),
        calibration == null ? null : calibration.calibratedAt());
  }
}
