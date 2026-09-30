package io.github.yourimartin.gatewai.infrastructure.cache;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.calibration.ConformalStatus;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecisionReason;
import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.decision.PromptHash;
import io.github.yourimartin.gatewai.domain.model.routing.RequestEmbeddingMemo;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
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
  private final DecisionMetricsRecorder metrics;

  CacheDecisionTracer(DecisionRecorder recorder,
                      DecisionMetricsRecorder metrics) {
    this.recorder = recorder;
    this.metrics = metrics;
  }

  /**
   * Records a hit or a miss, with the scores that separated them.
   *
   * @param reason why this was not a plain similarity lookup, or null
   * @param scope  the conversation scope the lookup ran in (ADR 0014)
   */
  void decided(String userText, List<Document> candidates, Document hit,
               double threshold, ConformalStatus conformalStatus,
               CacheDecisionReason reason, String scope) {
    try {
      Double best = score(candidates, 0);
      Double runnerUp = score(candidates, 1);

      publish(new CacheDecision(
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
          conformalStatus,
          reason,
          scope));
    } catch (RuntimeException e) {
      LOG.warn("Could not build cache decision: {}", e.toString());
    }
  }

  /** Records a request the cache never looked at, and why. */
  void bypassed(String userText, CacheDecisionReason reason, String scope) {
    record(userText, CacheOutcome.BYPASS, 0, reason, scope);
  }

  /** Records a lookup that failed; the request is served as if it missed. */
  void failed(String userText, double threshold, String scope) {
    record(userText, CacheOutcome.ERROR, threshold, null, scope);
  }

  private void record(String userText, CacheOutcome outcome, double threshold,
                      CacheDecisionReason reason, String scope) {
    try {
      publish(new CacheDecision(
          UUID.randomUUID(),
          correlationId(),
          Instant.now(),
          PromptHash.of(userText),
          outcome,
          0, null, threshold,
          null, null, null,
          embeddingModel(),
          null,
          reason,
          scope));
    } catch (RuntimeException e) {
      LOG.warn("Could not build cache decision: {}", e.toString());
    }
  }

  /**
   * Trace and metrics, from the same object (v2 batch 6). Metrics are published
   * here rather than from the recorder so that switching decision persistence
   * off does not also switch the dashboards off.
   */
  private void publish(CacheDecision decision) {
    recorder.record(decision);
    metrics.record(decision);
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
