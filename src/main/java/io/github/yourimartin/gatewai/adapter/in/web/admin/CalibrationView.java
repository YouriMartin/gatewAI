package io.github.yourimartin.gatewai.adapter.in.web.admin;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.calibration.ShadowFit;

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
 * @param client             the client this row applies to; null for a global row
 *                           (v4 D.3, ADR 0019)
 * @param source             LABELLED_SET or SHADOW; null when never calibrated
 * @param labelledBy         who labelled the cases it was fitted on, in words: a
 *                           shadow fit says it was a model, not a person
 * @param judgeModel         shadow: the judge whose verdicts were fitted on
 * @param rubricVersion      shadow: the rubric they were given under
 * @param labelFloor         shadow: the threshold in force for the scope when fitted
 * @param fitSummary         what the fit read and excluded; null on a row fitted
 *                           before v4 D.3
 */
public record CalibrationView(
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
    Instant calibratedAt,
    String client,
    String source,
    String labelledBy,
    String judgeModel,
    String rubricVersion,
    Double labelFloor,
    FitSummaryView fitSummary
) {

  static CalibrationView of(CalibrationState state) {
    ConformalCalibration calibration = state.calibration();
    ShadowFit fit = calibration == null ? null : calibration.shadowFit();
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
        calibration == null ? null : calibration.calibratedAt(),
        calibration == null ? null : calibration.clientId(),
        calibration == null ? null : calibration.source().name(),
        calibration == null ? null : labelledBy(calibration),
        fit == null ? null : fit.judgeModel(),
        fit == null ? null : fit.rubricVersion(),
        fit == null ? null : fit.labelFloor(),
        calibration == null ? null : FitSummaryView.of(calibration.fitSummary()));
  }

  /** The same wording as the shadow wrong-hit rate (v4 D.2). */
  private static String labelledBy(ConformalCalibration calibration) {
    ShadowFit fit = calibration.shadowFit();
    return fit == null ? "hand-labelled cases"
        : "judged by " + fit.judgeModel() + " (rubric " + fit.rubricVersion()
            + "), not by a human";
  }
}
