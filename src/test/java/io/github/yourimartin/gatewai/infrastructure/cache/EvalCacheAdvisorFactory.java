package io.github.yourimartin.gatewai.infrastructure.cache;

import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;

import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Lends the evaluation harness the <b>real</b> {@link SemanticCacheAdvisor}
 * (v4 batch A.1).
 *
 * <p>The advisor and its tracer are package-private, which is right: nothing
 * outside this adapter should build them. But the conversation evaluation exists
 * to measure what the cache actually does with a conversation — which text it
 * embeds, which filter it applies, what it stores — and a copy of that lookup in
 * the harness would keep reporting the old numbers after the advisor changed.
 * This factory lives in the package for that one reason, like
 * {@code EvalClassifierFactory} does for the router.
 *
 * <p>It builds the advisor with the shipped defaults of
 * {@link SemanticCacheProperties} (client namespacing on, no TTL); the threshold
 * comes from the {@link CalibrationUseCase}, as it does in production.
 */
public final class EvalCacheAdvisorFactory {

  private static final DecisionMetricsRecorder NO_METRICS = new DecisionMetricsRecorder() {
    @Override
    public void record(RoutingDecision decision) {
      // Metrics are not what the harness measures.
    }

    @Override
    public void record(CacheDecision decision) {
      // Metrics are not what the harness measures.
    }
  };

  private EvalCacheAdvisorFactory() {
  }

  /**
   * The production cache advisor over {@code vectorStore}.
   *
   * @param decisions receives every cache decision the advisor traces, so the
   *                  harness can read the outcome and the similarity it was taken on
   */
  public static CallAdvisor semanticCache(VectorStore vectorStore,
                                          CalibrationUseCase calibrations,
                                          DecisionRecorder decisions) {
    CacheDecisionTracer tracer = new CacheDecisionTracer(decisions, NO_METRICS);
    return new SemanticCacheAdvisor(vectorStore, new SemanticCacheProperties(), tracer,
        calibrations);
  }
}
