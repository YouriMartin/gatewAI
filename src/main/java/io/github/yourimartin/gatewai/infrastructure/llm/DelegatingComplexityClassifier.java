package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link Primary} {@link ComplexityClassifier}: dispatches to the
 * strategy selected in {@link ClassifierProperties}, read per call so admin
 * API changes apply on the next request. Each concrete classifier keeps its
 * own internal fallback (embedding/LLM degrade to the heuristic).
 *
 * <p>The justification is propagated verbatim, never reinterpreted: this class
 * knows which strategy was <em>configured</em>, and the outcome already says
 * which one <em>decided</em>.
 *
 * <p>This is also the seam the cascade lives in (v2 batch 4): {@code CASCADE}
 * chains the same three classifiers by increasing cost rather than choosing one
 * of them — see {@link CascadeComplexityClassifier}.
 */
@Component
@Primary
class DelegatingComplexityClassifier implements ComplexityClassifier {

  private final ClassifierProperties properties;
  private final HeuristicComplexityClassifier heuristic;
  private final EmbeddingComplexityClassifier embedding;
  private final LlmComplexityClassifier llm;
  private final CascadeComplexityClassifier cascade;

  DelegatingComplexityClassifier(ClassifierProperties properties,
                                 HeuristicComplexityClassifier heuristic,
                                 EmbeddingComplexityClassifier embedding,
                                 LlmComplexityClassifier llm,
                                 CascadeComplexityClassifier cascade) {
    this.properties = properties;
    this.heuristic = heuristic;
    this.embedding = embedding;
    this.llm = llm;
    this.cascade = cascade;
  }

  @Override
  public ClassificationOutcome classify(String userText) {
    return switch (properties.getStrategy()) {
      case HEURISTIC -> heuristic.classify(userText);
      case EMBEDDING -> embedding.classify(userText);
      case LLM -> llm.classify(userText);
      case CASCADE -> cascade.classify(userText);
    };
  }
}
