package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.yourimartin.gatewai.domain.model.ModelTier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DelegatingComplexityClassifierTest {

  private ClassifierProperties properties;
  private HeuristicComplexityClassifier heuristic;
  private EmbeddingComplexityClassifier embedding;
  private LlmComplexityClassifier llm;
  private DelegatingComplexityClassifier classifier;

  @BeforeEach
  void setUp() {
    properties = new ClassifierProperties();
    heuristic = mock(HeuristicComplexityClassifier.class);
    embedding = mock(EmbeddingComplexityClassifier.class);
    llm = mock(LlmComplexityClassifier.class);
    classifier = new DelegatingComplexityClassifier(
        properties, heuristic, embedding, llm);
  }

  @Test
  void heuristicStrategyDelegatesToHeuristic() {
    properties.setStrategy(ClassifierStrategy.HEURISTIC);
    when(heuristic.classify("hi")).thenReturn(ModelTier.LOCAL);

    assertEquals(ModelTier.LOCAL, classifier.classify("hi"));
    verify(embedding, never()).classify(anyString());
    verify(llm, never()).classify(anyString());
  }

  @Test
  void embeddingStrategyDelegatesToEmbedding() {
    properties.setStrategy(ClassifierStrategy.EMBEDDING);
    when(embedding.classify("hi")).thenReturn(ModelTier.CLOUD_ENTRY);

    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify("hi"));
    verify(heuristic, never()).classify(anyString());
    verify(llm, never()).classify(anyString());
  }

  @Test
  void llmStrategyDelegatesToLlm() {
    properties.setStrategy(ClassifierStrategy.LLM);
    when(llm.classify("hi")).thenReturn(ModelTier.CLOUD_PREMIUM);

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("hi"));
    verify(heuristic, never()).classify(anyString());
    verify(embedding, never()).classify(anyString());
  }

  @Test
  void strategyChangeAppliesOnNextCall() {
    properties.setStrategy(ClassifierStrategy.HEURISTIC);
    when(heuristic.classify("hi")).thenReturn(ModelTier.LOCAL);
    classifier.classify("hi");

    properties.setStrategy(ClassifierStrategy.EMBEDDING);
    when(embedding.classify("hi")).thenReturn(ModelTier.CLOUD_ENTRY);

    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify("hi"));
  }
}
