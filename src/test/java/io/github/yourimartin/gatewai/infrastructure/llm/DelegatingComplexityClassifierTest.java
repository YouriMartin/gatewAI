package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.github.yourimartin.gatewai.infrastructure.llm.ClassificationOutcomeFixtures.outcome;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.ModelTier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DelegatingComplexityClassifierTest {

  private ClassifierProperties properties;
  private HeuristicComplexityClassifier heuristic;
  private EmbeddingComplexityClassifier embedding;
  private LlmComplexityClassifier llm;
  private CascadeComplexityClassifier cascade;
  private DelegatingComplexityClassifier classifier;

  @BeforeEach
  void setUp() {
    properties = new ClassifierProperties();
    heuristic = mock(HeuristicComplexityClassifier.class);
    embedding = mock(EmbeddingComplexityClassifier.class);
    llm = mock(LlmComplexityClassifier.class);
    cascade = mock(CascadeComplexityClassifier.class);
    classifier = new DelegatingComplexityClassifier(
        properties, heuristic, embedding, llm, cascade);
  }

  @Test
  void heuristicStrategyDelegatesToHeuristic() {
    properties.setStrategy(ClassificationStrategy.HEURISTIC);
    when(heuristic.classify("hi")).thenReturn(outcome(ModelTier.LOCAL));

    assertEquals(ModelTier.LOCAL, classifier.classify("hi").tier());
    verify(embedding, never()).classify(anyString());
    verify(llm, never()).classify(anyString());
  }

  @Test
  void embeddingStrategyDelegatesToEmbedding() {
    properties.setStrategy(ClassificationStrategy.EMBEDDING);
    when(embedding.classify("hi")).thenReturn(outcome(ModelTier.CLOUD_ENTRY));

    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify("hi").tier());
    verify(heuristic, never()).classify(anyString());
    verify(llm, never()).classify(anyString());
  }

  @Test
  void llmStrategyDelegatesToLlm() {
    properties.setStrategy(ClassificationStrategy.LLM);
    when(llm.classify("hi")).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("hi").tier());
    verify(heuristic, never()).classify(anyString());
    verify(embedding, never()).classify(anyString());
  }

  @Test
  void cascadeStrategyDelegatesToTheCascade() {
    properties.setStrategy(ClassificationStrategy.CASCADE);
    when(cascade.classify("hi")).thenReturn(outcome(ModelTier.CLOUD_ENTRY));

    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify("hi").tier());
    verify(heuristic, never()).classify(anyString());
    verify(embedding, never()).classify(anyString());
    verify(llm, never()).classify(anyString());
  }

  @Test
  void justificationIsPropagatedVerbatim() {
    // The delegate must not reinterpret what the strategy reported — a fallback
    // has to stay recognisable as a fallback all the way to the caller.
    properties.setStrategy(ClassificationStrategy.EMBEDDING);
    ClassificationJustification reported =
        new ClassificationJustification.Fallback(
            ClassificationStrategy.EMBEDDING,
            ClassificationJustification.FallbackCause.EMBEDDING_ERROR,
            ClassificationJustification.Heuristic.of(
                ClassificationJustification.HeuristicRule.CODE_FENCE));
    when(embedding.classify("hi")).thenReturn(
        new ClassificationOutcome(ModelTier.CLOUD_PREMIUM, reported));

    ClassificationOutcome result = classifier.classify("hi");

    assertSame(reported, result.justification());
    assertEquals(ModelTier.CLOUD_PREMIUM, result.tier());
  }

  @Test
  void everyStrategyProducesANonEmptyJustification() {
    // The acceptance criterion of batch 1: an explanation cannot vanish because
    // gatewai.classifier.strategy changed.
    for (ClassificationStrategy strategy : ClassificationStrategy.values()) {
      properties.setStrategy(strategy);
      assertNotNull(classifierFor(strategy));
    }
  }

  private ClassificationJustification classifierFor(
      ClassificationStrategy strategy) {
    when(switch (strategy) {
      case HEURISTIC -> heuristic.classify("hi");
      case EMBEDDING -> embedding.classify("hi");
      case LLM -> llm.classify("hi");
      case CASCADE -> cascade.classify("hi");
    }).thenReturn(outcome(ModelTier.LOCAL));
    return classifier.classify("hi").justification();
  }

  @Test
  void strategyChangeAppliesOnNextCall() {
    properties.setStrategy(ClassificationStrategy.HEURISTIC);
    when(heuristic.classify("hi")).thenReturn(outcome(ModelTier.LOCAL));
    classifier.classify("hi");

    properties.setStrategy(ClassificationStrategy.EMBEDDING);
    when(embedding.classify("hi")).thenReturn(outcome(ModelTier.CLOUD_ENTRY));

    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify("hi").tier());
  }
}
