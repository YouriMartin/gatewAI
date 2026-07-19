package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link Primary} {@link ComplexityClassifier}: dispatches to the
 * strategy selected in {@link ClassifierProperties}, read per call so admin
 * API changes apply on the next request. Each concrete classifier keeps its
 * own internal fallback (embedding/LLM degrade to the heuristic).
 *
 * <p>This is also the seam for a future cascade mode (deterministic signals →
 * embedding routes → LLM on ambiguity, see {@code docs/technical/routing.md}).
 */
@Component
@Primary
class DelegatingComplexityClassifier implements ComplexityClassifier {

  private final ClassifierProperties properties;
  private final HeuristicComplexityClassifier heuristic;
  private final EmbeddingComplexityClassifier embedding;
  private final LlmComplexityClassifier llm;

  DelegatingComplexityClassifier(ClassifierProperties properties,
                                 HeuristicComplexityClassifier heuristic,
                                 EmbeddingComplexityClassifier embedding,
                                 LlmComplexityClassifier llm) {
    this.properties = properties;
    this.heuristic = heuristic;
    this.embedding = embedding;
    this.llm = llm;
  }

  @Override
  public ModelTier classify(String userText) {
    return switch (properties.getStrategy()) {
      case HEURISTIC -> heuristic.classify(userText);
      case EMBEDDING -> embedding.classify(userText);
      case LLM -> llm.classify(userText);
    };
  }
}
