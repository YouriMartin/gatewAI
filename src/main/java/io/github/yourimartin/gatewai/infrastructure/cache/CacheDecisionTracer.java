package io.github.yourimartin.gatewai.infrastructure.cache;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.ConformalStatus;
import io.github.yourimartin.gatewai.domain.model.PromptHash;
import io.github.yourimartin.gatewai.domain.model.RequestContext;
import io.github.yourimartin.gatewai.domain.model.RequestEmbeddingMemo;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Turns a cache lookup into a {@link CacheDecision} (v2 batch 2).
 *
 * <p>Kept out of {@link SemanticCacheAdvisor} so the advisor keeps reading as
 * cache logic. Nothing here may throw: every method is best-effort, because a
 * request must never fail over its own explanation.
 */
@Component
class CacheDecisionTracer {

  private static final Logger LOG =
      LoggerFactory.getLogger(CacheDecisionTracer.class);

  private final DecisionRecorder recorder;

  CacheDecisionTracer(DecisionRecorder recorder) {
    this.recorder = recorder;
  }

  /** Records a hit or a miss, with the scores that separated them. */
  void decided(String userText, List<Document> candidates, Document hit,
               double threshold, ConformalStatus conformalStatus) {
    try {
      Double best = score(candidates, 0);
      Double runnerUp = score(candidates, 1);

      recorder.record(new CacheDecision(
          UUID.randomUUID(),
          correlationId(),
          Instant.now(),
          PromptHash.of(userText),
          hit != null ? CacheOutcome.HIT : CacheOutcome.MISS,
          best == null ? 0 : best,
          runnerUp,
          threshold,
          hit == null ? null : hit.getId(),
          hit == null ? null : ageSeconds(hit),
          hit == null ? null : originCorrelationId(hit),
          embeddingModel(),
          conformalStatus));
    } catch (RuntimeException e) {
      LOG.warn("Could not build cache decision: {}", e.toString());
    }
  }

  /** Records a request the cache never looked at (blank prompt). */
  void bypassed(String userText) {
    record(userText, CacheOutcome.BYPASS, 0);
  }

  /** Records a lookup that failed; the request is served as if it missed. */
  void failed(String userText, double threshold) {
    record(userText, CacheOutcome.ERROR, threshold);
  }

  private void record(String userText, CacheOutcome outcome, double threshold) {
    try {
      recorder.record(new CacheDecision(
          UUID.randomUUID(),
          correlationId(),
          Instant.now(),
          PromptHash.of(userText),
          outcome,
          0, null, threshold,
          null, null, null,
          embeddingModel(),
          null));
    } catch (RuntimeException e) {
      LOG.warn("Could not build cache decision: {}", e.toString());
    }
  }

  private static Double score(List<Document> candidates, int index) {
    return candidates == null || candidates.size() <= index
        ? null : candidates.get(index).getScore();
  }

  /** How old the served entry was, from the timestamp stored beside it. */
  private static Long ageSeconds(Document hit) {
    Object createdAt = hit.getMetadata().get(SemanticCacheAdvisor.CREATED_AT_KEY);
    if (!(createdAt instanceof Number millis)) {
      return null;
    }
    long age = (Instant.now().toEpochMilli() - millis.longValue()) / 1000;
    return Math.max(0, age);
  }

  /**
   * The correlation id of the request that wrote the served entry — what makes
   * a hit auditable back to the routing decision behind the answer.
   */
  private static String originCorrelationId(Document hit) {
    Object value = hit.getMetadata().get(SemanticCacheAdvisor.CORRELATION_ID_KEY);
    return value instanceof String id ? id : null;
  }

  /**
   * The embedding model behind this request's vector. Read from the per-request
   * memo, which the vector store's own lookup has already populated — so this
   * is the model that actually ran, not the one configured.
   */
  private static String embeddingModel() {
    return RequestEmbeddingMemo.current()
        .flatMap(RequestEmbeddingMemo::embeddingModelId)
        .orElse(null);
  }

  private static String correlationId() {
    return RequestContext.CURRENT.isBound()
        ? RequestContext.CURRENT.get().traceId() : null;
  }
}
