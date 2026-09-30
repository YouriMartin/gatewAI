package io.github.yourimartin.gatewai.infrastructure.cache;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.EmbeddingWindow;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

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

  private static final ModelRegistry NO_MODELS = new ModelRegistry() {
    @Override
    public List<ModelDefinition> allModels() {
      return List.of();
    }

    @Override
    public Optional<ModelDefinition> findByKey(String key) {
      return Optional.empty();
    }

    @Override
    public Optional<ModelDefinition> findByModelId(String modelId) {
      return Optional.empty();
    }

    @Override
    public List<ModelDefinition> findByTier(ModelTier tier) {
      return List.of();
    }
  };

  private EvalCacheAdvisorFactory() {
  }

  /**
   * The production cache advisor over {@code vectorStore}.
   *
   * <p>No model is registered: the conversation cases pin none, so every
   * requested name is routed and shares a scope, as unregistered names do.
   *
   * @param decisions receives every cache decision the advisor traces, so the
   *                  harness can read the outcome and the similarity it was taken on
   * @param window    the embedding window — the real one, since the exact-match
   *                  rule for long prompts is part of what is measured
   */
  public static CallAdvisor semanticCache(VectorStore vectorStore,
                                          CalibrationUseCase calibrations,
                                          DecisionRecorder decisions,
                                          EmbeddingWindow window) {
    CacheDecisionTracer tracer = new CacheDecisionTracer(decisions, NO_METRICS);
    return new SemanticCacheAdvisor(vectorStore, new SemanticCacheProperties(), tracer,
        calibrations, NO_MODELS, window);
  }
}
