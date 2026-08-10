package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.FallbackCause;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * V2 classifier: asks a small/cheap model to label the request's complexity,
 * returned as a structured {@link ClassificationResult} via Spring AI's
 * {@code entity} (Structured Outputs). Strategy selection lives in
 * {@link DelegatingComplexityClassifier}; here, when the LLM call fails it
 * falls back to the heuristic so routing never breaks because a model is
 * unreachable.
 */
@Component
class LlmComplexityClassifier implements ComplexityClassifier {

  private static final Logger LOG =
      LoggerFactory.getLogger(LlmComplexityClassifier.class);

  private final ChatClient classifierClient;
  private final ClassifierProperties properties;
  private final ModelRegistryProperties modelProperties;
  private final HeuristicComplexityClassifier heuristic;

  LlmComplexityClassifier(
      @Qualifier("classifierClient") ChatClient classifierClient,
      ClassifierProperties properties,
      ModelRegistryProperties modelProperties,
      HeuristicComplexityClassifier heuristic) {
    this.classifierClient = classifierClient;
    this.properties = properties;
    this.modelProperties = modelProperties;
    this.heuristic = heuristic;
  }

  @Override
  public ClassificationOutcome classify(String userText) {
    if (userText == null || userText.isBlank()) {
      // Not a fallback: no strategy can classify nothing.
      return heuristic.classify(userText);
    }

    try {
      ClassificationResult result = classifierClient.prompt()
          .system(properties.getSystemPrompt())
          .user(userText)
          .call()
          .entity(ClassificationResult.class);

      if (result == null || result.tier() == null) {
        LOG.warn("LLM classifier returned no tier, falling back");
        return fallback(userText, FallbackCause.NO_TIER_RETURNED);
      }

      LOG.debug("LLM classified request as {} ({})",
          result.tier(), result.reasoning());
      return new ClassificationOutcome(result.tier(),
          new ClassificationJustification.Llm(
              result.reasoning(), classifierModelId()));
    } catch (RuntimeException e) {
      LOG.warn("LLM classification failed ({}), falling back",
          e.getMessage());
      return fallback(userText, FallbackCause.LLM_ERROR);
    }
  }

  private ClassificationOutcome fallback(String userText, FallbackCause cause) {
    if (properties.isFallbackToHeuristic()) {
      return heuristic.classify(userText)
          .asFallbackFrom(ClassificationStrategy.LLM, cause);
    }
    // Fail safe toward answer quality: nothing classified this request, so the
    // trace must not present premium as a judgement.
    return new ClassificationOutcome(ModelTier.CLOUD_PREMIUM,
        new ClassificationJustification.FailSafe(
            ClassificationStrategy.LLM, cause));
  }

  /**
   * The model that actually answers, resolved the same way the classifier
   * client is built: the configured id, or the registry's entry-tier model when
   * it is blank.
   */
  private String classifierModelId() {
    String configured = properties.getModelId();
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    try {
      return ChatClientConfiguration.resolveModelId(
          modelProperties, ModelTier.CLOUD_ENTRY);
    } catch (IllegalStateException e) {
      // The client could not have been built without one, but provenance must
      // never be the thing that fails a classification.
      return null;
    }
  }
}
