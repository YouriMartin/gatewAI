package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * V2 classifier: asks a small/cheap model to label the request's complexity,
 * returned as a structured {@link ClassificationResult} via Spring AI's
 * {@code entity} (Structured Outputs).
 *
 * <p>This is the {@link Primary} {@link ComplexityClassifier}. Behaviour is
 * driven by {@link ClassifierProperties} (read per call, so configurable at
 * runtime): when the strategy is {@code HEURISTIC} it simply delegates to the
 * {@link HeuristicComplexityClassifier}; when the LLM call fails it falls back
 * to the heuristic so routing never breaks because a model is unreachable.
 */
@Component
@Primary
class LlmComplexityClassifier implements ComplexityClassifier {

  private static final Logger LOG =
      LoggerFactory.getLogger(LlmComplexityClassifier.class);

  private final ChatClient classifierClient;
  private final ClassifierProperties properties;
  private final HeuristicComplexityClassifier heuristic;

  LlmComplexityClassifier(
      @Qualifier("classifierClient") ChatClient classifierClient,
      ClassifierProperties properties,
      HeuristicComplexityClassifier heuristic) {
    this.classifierClient = classifierClient;
    this.properties = properties;
    this.heuristic = heuristic;
  }

  @Override
  public ModelTier classify(String userText) {
    if (userText == null || userText.isBlank()) {
      return ModelTier.LOCAL;
    }

    if (properties.getStrategy() != ClassifierStrategy.LLM) {
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
        return fallback(userText);
      }

      LOG.debug("LLM classified request as {} ({})",
          result.tier(), result.reasoning());
      return result.tier();
    } catch (RuntimeException e) {
      LOG.warn("LLM classification failed ({}), falling back",
          e.getMessage());
      return fallback(userText);
    }
  }

  private ModelTier fallback(String userText) {
    return properties.isFallbackToHeuristic()
        ? heuristic.classify(userText)
        : ModelTier.CLOUD_PREMIUM;
  }
}
