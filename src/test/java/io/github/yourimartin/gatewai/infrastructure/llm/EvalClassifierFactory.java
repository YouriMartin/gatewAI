package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Lends the evaluation harness (v2 batch 5) the <b>real</b> classifiers.
 *
 * <p>{@code EmbeddingComplexityClassifier}, {@code HeuristicComplexityClassifier}
 * and {@code ClassifierProperties} are package-private, which is right: nothing
 * outside this adapter should build them. But an evaluation that re-implemented
 * route ranking, the similarity threshold or the hand-over to the heuristic
 * would be measuring a copy of the router, and would keep reporting good numbers
 * after the real one regressed. This factory lives in the package for that one
 * reason, and is the only test-scope class allowed to.
 *
 * <p>It is deliberately thin: it builds the same objects Spring builds, from a
 * {@link RoutingConfig}, through the same {@code ClassifierRoutingConfigAdapter}
 * the admin API writes through.
 */
public final class EvalClassifierFactory {

  private EvalClassifierFactory() {
  }

  /**
   * The routing configuration as it ships, before any
   * {@code application.properties} override — the class defaults of
   * {@code ClassifierProperties}, including the bilingual default routes.
   */
  public static RoutingConfig defaultRoutingConfig() {
    return new ClassifierRoutingConfigAdapter(new ClassifierProperties()).get();
  }

  /** The production embedding classifier, with its heuristic fallback wired in. */
  public static ComplexityClassifier embeddingClassifier(
      EmbeddingModel embeddingModel, RoutingConfig config) {
    ClassifierProperties properties = propertiesOf(config);
    return new EmbeddingComplexityClassifier(
        embeddingModel, properties, new HeuristicComplexityClassifier(properties));
  }

  /** The production heuristic classifier, for the zero-cost baseline. */
  public static ComplexityClassifier heuristicClassifier(RoutingConfig config) {
    return new HeuristicComplexityClassifier(propertiesOf(config));
  }

  private static ClassifierProperties propertiesOf(RoutingConfig config) {
    ClassifierProperties properties = new ClassifierProperties();
    new ClassifierRoutingConfigAdapter(properties).update(config);
    return properties;
  }
}
