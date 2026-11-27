package io.github.yourimartin.gatewai.domain.model.calibration;

import java.time.Instant;
import java.util.Objects;

import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeVerdict;

/**
 * One shadow would-hit that went to the judge, joined with the cache decision it
 * grades (v4 D.3, ADR 0019): the judge's side from {@code would_hit_assessment},
 * the cache's side from the {@code SHADOW} {@code cache_decision} row of the same
 * request. Numbers and labels only, like both rows it comes from.
 *
 * @param clientId          the shadow client
 * @param createdAt         when the would-hit was assessed
 * @param judgeOutcome      {@code JUDGED} or {@code FAILED}; a would-hit the judge
 *                          never saw is not a label
 * @param verdict           the judge's verdict, null unless judged
 * @param judgeModel        the registry model that judged
 * @param rubricVersion     the rubric it judged under
 * @param similarity        the query similarity the cache decided on; null when the
 *                          decision row is missing (purged or never recorded)
 * @param recordedThreshold the threshold that decision was taken under; null when
 *                          the decision row is missing
 * @param embeddingModel    the model behind {@code similarity}
 */
public record ShadowLabel(
    String clientId,
    Instant createdAt,
    JudgeOutcome judgeOutcome,
    JudgeVerdict verdict,
    String judgeModel,
    String rubricVersion,
    Double similarity,
    Double recordedThreshold,
    String embeddingModel
) {

  public ShadowLabel {
    Objects.requireNonNull(judgeOutcome, "judgeOutcome");
    if (judgeOutcome == JudgeOutcome.NOT_SAMPLED) {
      throw new IllegalArgumentException("a would-hit the judge never saw is not a label");
    }
  }

  /** Whether the cache decision this label grades was found. */
  public boolean hasDecision() {
    return similarity != null && recordedThreshold != null;
  }
}
