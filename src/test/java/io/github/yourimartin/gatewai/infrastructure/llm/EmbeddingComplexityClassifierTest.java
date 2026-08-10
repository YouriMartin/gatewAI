package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.github.yourimartin.gatewai.infrastructure.llm.ClassificationOutcomeFixtures.outcome;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
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
    properties.setStrategy(ClassificationStrategy.EMBEDDING);
    properties.setRoutes(new ArrayList<>(List.of(
        new ClassifierProperties.Route("chat", ModelTier.LOCAL,
            List.of("hello there")),
        new ClassifierProperties.Route("code", ModelTier.CLOUD_PREMIUM,
            List.of("refactor this service")))));

    Mockito.lenient().when(embeddingModel.embed(List.of("hello there")))
        .thenReturn(List.of(CHAT_AXIS));
    Mockito.lenient().when(embeddingModel.embed(List.of("refactor this service")))
        .thenReturn(List.of(CODE_AXIS));

    // Blank input is the heuristic's shared short-circuit for every strategy.
    Mockito.lenient().when(heuristic.classify("  ")).thenReturn(
        new ClassificationOutcome(ModelTier.LOCAL,
            ClassificationJustification.Heuristic.of(
                ClassificationJustification.HeuristicRule.BLANK_TEXT)));

    classifier = new EmbeddingComplexityClassifier(
        embeddingModel, properties, heuristic);
  }

  @Test
  void blankTextReturnsLocalWithoutEmbedding() {
    assertEquals(ModelTier.LOCAL, classifier.classify("  ").tier());
    verify(embeddingModel, never()).embed(anyString());
  }

  @Test
  void picksTierOfClosestRouteExample() {
    when(embeddingModel.embed("please refactor my code"))
        .thenReturn(new float[] {0.1f, 0.9f});

    assertEquals(ModelTier.CLOUD_PREMIUM,
        classifier.classify("please refactor my code").tier());
    verify(heuristic, never()).classify(anyString());
  }

  @Test
  void picksLocalRouteForChatLikeQuery() {
    when(embeddingModel.embed("hi!")).thenReturn(new float[] {0.95f, 0.05f});

    assertEquals(ModelTier.LOCAL, classifier.classify("hi!").tier());
  }

  @Test
  void fallsBackToHeuristicBelowThreshold() {
    properties.setRouteSimilarityThreshold(0.9);
    when(embeddingModel.embed("ambiguous")).thenReturn(new float[] {0.7f, 0.7f});
    when(heuristic.classify("ambiguous")).thenReturn(outcome(ModelTier.CLOUD_ENTRY));

    assertEquals(ModelTier.CLOUD_ENTRY, classifier.classify("ambiguous").tier());
  }

  @Test
  void fallsBackToHeuristicWhenNoRouteConfigured() {
    properties.setRoutes(new ArrayList<>());
    when(heuristic.classify("anything")).thenReturn(outcome(ModelTier.LOCAL));

    assertEquals(ModelTier.LOCAL, classifier.classify("anything").tier());
    verify(embeddingModel, never()).embed(anyString());
  }

  @Test
  void fallsBackToHeuristicWhenEmbeddingFails() {
    when(embeddingModel.embed(anyString()))
        .thenThrow(new RuntimeException("ollama unreachable"));
    when(heuristic.classify("hello")).thenReturn(outcome(ModelTier.LOCAL));

    assertEquals(ModelTier.LOCAL, classifier.classify("hello").tier());
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

    assertEquals(ModelTier.LOCAL, classifier.classify("hello again").tier());
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

    assertEquals(ModelTier.CLOUD_PREMIUM, classifier.classify("refactor").tier());
    verify(embeddingModel, never()).embed(List.of("x"));
  }

  // ---- Justification (v2 batch 1) ----

  @Test
  void justificationRanksEveryRouteWithItsClosestExample() {
    when(embeddingModel.embed("please refactor my code"))
        .thenReturn(new float[] {0.1f, 0.9f});

    var embedding = assertInstanceOf(
        ClassificationJustification.Embedding.class,
        classifier.classify("please refactor my code").justification());

    assertEquals(ClassificationStrategy.EMBEDDING, embedding.strategy());
    assertEquals(2, embedding.candidates().size());
    assertEquals(properties.getRouteSimilarityThreshold(),
        embedding.threshold());

    var winner = embedding.candidates().getFirst();
    assertEquals("code", winner.route());
    assertEquals(ModelTier.CLOUD_PREMIUM, winner.tier());
    assertEquals("refactor this service", winner.bestUtterance());
    assertEquals(1, winner.rank());
    assertEquals(winner.score(), embedding.topScore());

    var runnerUp = embedding.candidates().get(1);
    assertEquals("chat", runnerUp.route());
    assertEquals(2, runnerUp.rank());
    assertTrue(runnerUp.score() < winner.score());
  }

  @Test
  void marginIsTheGapToTheRunnerUpNotTheRawScore() {
    // Orthogonal axes: the query sits almost entirely on the code axis, so the
    // decision is confident even though the raw score is not near 1.
    when(embeddingModel.embed("q")).thenReturn(new float[] {0.2f, 0.98f});

    var embedding = assertInstanceOf(
        ClassificationJustification.Embedding.class,
        classifier.classify("q").justification());

    double expected = embedding.candidates().getFirst().score()
        - embedding.candidates().get(1).score();
    assertEquals(expected, embedding.margin(), 1e-9);
  }

  @Test
  void belowThresholdFallbackKeepsTheRouteScoresThatExplainIt() {
    properties.setRouteSimilarityThreshold(0.9);
    when(embeddingModel.embed("ambiguous")).thenReturn(new float[] {0.7f, 0.7f});
    when(heuristic.classify("ambiguous")).thenReturn(outcome(ModelTier.CLOUD_ENTRY));

    var fallback = assertInstanceOf(ClassificationJustification.Fallback.class,
        classifier.classify("ambiguous").justification());

    assertEquals(ClassificationStrategy.EMBEDDING, fallback.fallbackFrom());
    assertEquals(ClassificationJustification.FallbackCause.BELOW_THRESHOLD,
        fallback.cause());
    // The heuristic decided...
    assertEquals(ClassificationStrategy.HEURISTIC, fallback.strategy());
    assertInstanceOf(ClassificationJustification.Heuristic.class,
        fallback.effective());
    // ...and the route scores explain why it had to.
    var scores = assertInstanceOf(ClassificationJustification.Embedding.class,
        fallback.evidence());
    assertEquals(0.9, scores.threshold());
    assertTrue(scores.topScore() < 0.9);
    assertEquals(2, scores.candidates().size());
  }

  @Test
  void embeddingFailureIsDistinguishableFromABelowThresholdMiss() {
    when(embeddingModel.embed(anyString()))
        .thenThrow(new RuntimeException("ollama unreachable"));
    when(heuristic.classify("hello")).thenReturn(outcome(ModelTier.LOCAL));

    var fallback = assertInstanceOf(ClassificationJustification.Fallback.class,
        classifier.classify("hello").justification());

    assertEquals(ClassificationJustification.FallbackCause.EMBEDDING_ERROR,
        fallback.cause());
  }

  @Test
  void missingRoutesAreReportedAsTheirOwnCause() {
    properties.setRoutes(new ArrayList<>());
    when(heuristic.classify("anything")).thenReturn(outcome(ModelTier.LOCAL));

    var fallback = assertInstanceOf(ClassificationJustification.Fallback.class,
        classifier.classify("anything").justification());

    assertEquals(ClassificationJustification.FallbackCause.NO_ROUTES_CONFIGURED,
        fallback.cause());
    // Nothing was computed before giving up, so there is no evidence to show.
    assertNull(fallback.evidence());
  }
}
