package io.github.yourimartin.gatewai.domain.port.out;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.calibration.ShadowLabel;

/**
 * The shadow labels a cache calibration can be fitted on (v4 D.3, ADR 0019): the
 * judge's verdicts on would-hits, joined with the cache decisions they grade.
 * Reads whatever the decision retention has kept.
 */
public interface ShadowLabelHistory {

  /**
   * The would-hits that went to the judge, judged or failed.
   *
   * @param clientId one client's, or null for every shadow client's
   */
  List<ShadowLabel> labels(String clientId);

  /**
   * The would-hits the judge never saw, counted beside the labels.
   *
   * @param clientId one client's, or null for every shadow client's
   */
  long notSampled(String clientId);

  /**
   * Shadow decisions whose prediction set was ambiguous at {@code floor} and
   * would be a singleton at {@code qhat}: the new threshold would serve them,
   * though no label speaks for them (ADR 0019, Consequences). Only decisions
   * recorded under a threshold at or below {@code floor}, on similarities from
   * {@code embeddingModel}, are counted, like the labels.
   *
   * @param clientId one client's, or null for every shadow client's
   */
  long ambiguousBecomingServable(String clientId, String embeddingModel, double floor,
                                 double qhat);
}
