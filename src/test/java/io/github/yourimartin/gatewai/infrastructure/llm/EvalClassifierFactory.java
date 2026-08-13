package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;
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

  /**
   * The production embedding classifier, with its heuristic fallback wired in
   * and the calibration it should consult — which is how the harness scores the
   * same classifier at the fixed threshold and at a calibrated one (v2 batch 3).
   */
  public static ComplexityClassifier embeddingClassifier(
      EmbeddingModel embeddingModel, RoutingConfig config,
      CalibrationUseCase calibrations) {
    ClassifierProperties properties = propertiesOf(config);
    return new EmbeddingComplexityClassifier(embeddingModel, properties,
        new HeuristicComplexityClassifier(properties), calibrations);
  }

  /** The production heuristic classifier, for the zero-cost baseline. */
  public static ComplexityClassifier heuristicClassifier(RoutingConfig config) {
    return new HeuristicComplexityClassifier(propertiesOf(config));
  }

  /**
   * The production cascade (v2 batch 4), with the <b>escalation gates it really
   * uses</b> and a stand-in at level 3.
   *
   * <p>Levels 1 and 2 and both gates are the shipped code, which is what the
   * escalation rate measures: how often the gateway would pay for a model call.
   * Level 3 cannot be: the harness is hermetic and there is no model server, so
   * the classifier model is replaced by the heuristic. The consequence is
   * explicit in the report — the escalation rate is exact, the accuracy is a
   * <em>lower bound</em>, the one where escalating buys nothing at all.
   *
   * @param marginBand the ambiguity band to score, so the report can sweep it
   */
  public static ComplexityClassifier cascadeClassifier(
      EmbeddingModel embeddingModel, RoutingConfig config,
      CalibrationUseCase calibrations, double marginBand) {

    ClassifierProperties properties = propertiesOf(config);
    properties.setCascadeMarginBand(marginBand);
    HeuristicComplexityClassifier heuristic =
        new HeuristicComplexityClassifier(properties);
    return new CascadeComplexityClassifier(properties, heuristic,
        new EmbeddingComplexityClassifier(embeddingModel, properties, heuristic,
            calibrations),
        new HeuristicLevelThree(properties, heuristic), calibrations);
  }

  /**
   * Level 3 with the model taken out: it answers like the heuristic would.
   *
   * <p>A subclass rather than a mock so the cascade is wired exactly as Spring
   * wires it, and so this file stays the only place where the harness knows
   * anything about how the classifiers are built.
   */
  private static final class HeuristicLevelThree extends LlmComplexityClassifier {

    private final HeuristicComplexityClassifier heuristic;

    private HeuristicLevelThree(ClassifierProperties properties,
                                HeuristicComplexityClassifier heuristic) {
      super(null, properties, null, heuristic);
      this.heuristic = heuristic;
    }

    @Override
    public ClassificationOutcome classify(String userText) {
      return heuristic.classify(userText);
    }
  }

  private static ClassifierProperties propertiesOf(RoutingConfig config) {
    ClassifierProperties properties = new ClassifierProperties();
    new ClassifierRoutingConfigAdapter(properties).update(config);
    return properties;
  }
}
