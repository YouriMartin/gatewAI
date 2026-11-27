package io.github.yourimartin.gatewai.domain.model.calibration;

/** Where a calibration's labels came from (v4 D.3, ADR 0019). */
public enum CalibrationSource {

  /**
   * The hand-labelled cases ({@code gatewai.conformal.routing-cases} /
   * {@code cache-cases}). The only source before v4 D.3, and the only one routing
   * is ever fitted on.
   */
  LABELLED_SET,

  /**
   * The judge's verdicts on shadow would-hits (v4 D.2). Cache only: the judge
   * grades cached answers, not routes. The labels are a model's, not a person's.
   */
  SHADOW
}
