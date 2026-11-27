package io.github.yourimartin.gatewai.domain.model.calibration;

/**
 * What a {@link CalibrationSource#SHADOW} calibration was fitted on (v4 D.3,
 * ADR 0019): one judge, one rubric, one floor. Labels from two judges or two
 * rubrics are never pooled, so a calibration names exactly one of each.
 *
 * @param judgeModel    the registry model whose verdicts were fitted on
 * @param rubricVersion the judge rubric those verdicts were given under
 * @param labelFloor    the threshold in force for the scope when it was fitted:
 *                      only labels recorded at or below it, and scoring at or
 *                      above it, counted
 */
public record ShadowFit(String judgeModel, String rubricVersion, double labelFloor) {

  public ShadowFit {
    if (judgeModel == null || judgeModel.isBlank()) {
      throw new IllegalArgumentException("a shadow fit names its judge model");
    }
    if (rubricVersion == null || rubricVersion.isBlank()) {
      throw new IllegalArgumentException("a shadow fit names its rubric version");
    }
    if (!Double.isFinite(labelFloor)) {
      throw new IllegalArgumentException("labelFloor must be finite, was " + labelFloor);
    }
  }
}
