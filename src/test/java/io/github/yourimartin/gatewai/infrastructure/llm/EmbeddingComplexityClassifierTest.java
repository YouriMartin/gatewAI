package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ModelTier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;

class EmbeddingComplexityClassifierTest {

  private static final float[] CHAT_AXIS = {1f, 0f};
  private static final float[] CODE_AXIS = {0f, 1f};

  private EmbeddingModel embeddingModel;
  private ClassifierProperties properties;
  private HeuristicComplexityClassifier heuristic;
  private EmbeddingComplexityClassifier classifier;

  @BeforeEach
  void setUp() {
    embeddingModel = mock(EmbeddingModel.class);
    heuristic = mock(HeuristicComplexityClassifier.class);

    properties = new ClassifierProperties();
    properties.setStrategy(ClassifierStrategy.EMBEDDING);
    properties.setRoutes(new ArrayList<>(List.of(
        new ClassifierProperties.Route("chat", ModelTier.LOCAL,
            List.of("hello there")),
        new ClassifierProperties.Route("code", ModelTier.CLOUD_PREMIUM,
            List.of("refactor this service")))));

    Mockito.lenient().when(embeddingModel.embed(List.of("hello there")))
        .thenReturn(List.of(CHAT_AXIS));
    Mockito.lenient().when(embeddingModel.embed(List.of("refactor this service")))
        .thenReturn(List.of(CODE_AXIS));

    classifier = new EmbeddingComplexityClassifier(
        embeddingModel, properties, heuristic);
  }

  @Test
  void blankTextReturnsLocalWithoutEmbedding() {
    assertEquals(ModelTier.LOCAL, classifier.classify("  "));
    verify(embeddingModel, never()).embed(anyString());
  }

  @Test
  void picksTierOfClosestRouteExample() {
    when(embeddingModel.embed("please refactor my code"))
        .thenReturn(new float[] {0.1f, 0.9f});

    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("please refactor my code"));
    verify(heuristic, never()).classify(anyString());
  }

  @Test
  void picksLocalRouteForChatLikeQuery() {
    when(embeddingModel.embed("hi!")).thenReturn(new float[] {0.95f, 0.05f});

    assertEquals(ModelTier.LOCAL, classifier.classify("hi!"));
  }

  @Test
  void fallsBackToHeuristicBelowThreshold() {
    properties.setRouteSimilarityThreshold(0.9);
    when(embeddingModel.embed("ambiguous")).thenReturn(new float[] {0.7f, 0.7f});
    when(heuristic.classify("ambiguous")).thenReturn(ModelTier.CLOUD_ENTRY);

    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify("ambiguous"));
  }

  @Test
  void fallsBackToHeuristicWhenNoRouteConfigured() {
    properties.setRoutes(new ArrayList<>());
    when(heuristic.classify("anything")).thenReturn(ModelTier.LOCAL);

    assertEquals(ModelTier.LOCAL, classifier.classify("anything"));
    verify(embeddingModel, never()).embed(anyString());
  }

  @Test
  void fallsBackToHeuristicWhenEmbeddingFails() {
    when(embeddingModel.embed(anyString()))
        .thenThrow(new RuntimeException("ollama unreachable"));
    when(heuristic.classify("hello")).thenReturn(ModelTier.LOCAL);

    assertEquals(ModelTier.LOCAL, classifier.classify("hello"));
  }

  @Test
  void reusesRouteIndexWhileConfigUnchanged() {
    when(embeddingModel.embed(anyString())).thenReturn(CHAT_AXIS);

    classifier.classify("first");
    classifier.classify("second");

    verify(embeddingModel, times(2)).embed(anyList());
  }

  @Test
  void rebuildsRouteIndexWhenRoutesChange() {
    when(embeddingModel.embed(anyString())).thenReturn(CHAT_AXIS);
    classifier.classify("first");

    properties.setRoutes(new ArrayList<>(List.of(
        new ClassifierProperties.Route("greetings", ModelTier.LOCAL,
            List.of("good morning")))));
    when(embeddingModel.embed(List.of("good morning")))
        .thenReturn(List.of(CHAT_AXIS));

    assertEquals(ModelTier.LOCAL, classifier.classify("hello again"));
    verify(embeddingModel).embed(List.of("good morning"));
  }

  @Test
  void ignoresRoutesWithoutTierOrExamples() {
    properties.setRoutes(new ArrayList<>(List.of(
        new ClassifierProperties.Route("broken", null, List.of("x")),
        new ClassifierProperties.Route("empty", ModelTier.LOCAL, List.of()),
        new ClassifierProperties.Route("code", ModelTier.CLOUD_PREMIUM,
            List.of("refactor this service")))));
    when(embeddingModel.embed("refactor")).thenReturn(CODE_AXIS);

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("refactor"));
    verify(embeddingModel, never()).embed(List.of("x"));
  }
}
