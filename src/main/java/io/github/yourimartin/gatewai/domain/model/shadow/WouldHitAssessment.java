package io.github.yourimartin.gatewai.domain.model.shadow;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * How one shadow would-hit compares with the answer the client actually received
 * (v4 D.2). Holds numbers and labels only: both answers are compared in memory
 * and discarded, so no row of this kind can carry answer text.
 *
 * @param id                 surrogate id
 * @param correlationId      the shadow request's, joining its {@code SHADOW} cache
 *                           decision and its {@code request_log} row
 * @param clientId           the shadow client
 * @param createdAt          when it was assessed
 * @param answerSimilarity   cosine similarity of the two answers under the
 *                           in-process embedding model; null when it could not be
 *                           computed
 * @param beyondWindow       true when either answer is longer than the embedding
 *                           window, so the similarity only covers its opening
 * @param embeddingModel     the model behind {@code answerSimilarity}
 * @param judgeOutcome       whether the judge saw it
 * @param verdict            the judge's verdict, non-null exactly when
 *                           {@code judgeOutcome} is {@code JUDGED}
 * @param judgeModel         the registry model that judged, null when not sampled
 * @param rubricVersion      the {@link JudgeRubric#VERSION} it was judged under,
 *                           null when not sampled
 * @param judgeCorrelationId the judge call's own {@code request_log} correlation id,
 *                           null when no call was made
 */
public record WouldHitAssessment(
    UUID id,
    String correlationId,
    String clientId,
    Instant createdAt,
    Double answerSimilarity,
    boolean beyondWindow,
    String embeddingModel,
    JudgeOutcome judgeOutcome,
    JudgeVerdict verdict,
    String judgeModel,
    String rubricVersion,
    String judgeCorrelationId
) {

  public WouldHitAssessment {
    Objects.requireNonNull(judgeOutcome, "judgeOutcome");
    if ((judgeOutcome == JudgeOutcome.JUDGED) != (verdict != null)) {
      throw new IllegalArgumentException(
          "a verdict is recorded exactly when the would-hit was judged");
    }
  }
}
