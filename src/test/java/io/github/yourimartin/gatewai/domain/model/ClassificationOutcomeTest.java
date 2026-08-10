package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.FallbackCause;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.HeuristicRule;

import org.junit.jupiter.api.Test;

class ClassificationOutcomeTest {

  private static final ClassificationJustification HEURISTIC =
      ClassificationJustification.Heuristic.of(HeuristicRule.CODE_FENCE);

  @Test
  void tierAndJustificationAreBothRequired() {
    assertThrows(IllegalArgumentException.class,
        () -> new ClassificationOutcome(null, HEURISTIC));
    assertThrows(IllegalArgumentException.class,
        () -> new ClassificationOutcome(ModelTier.LOCAL, null));
  }

  @Test
  void fallbackKeepsTheTierAndWrapsTheJustification() {
    ClassificationOutcome decided =
        new ClassificationOutcome(ModelTier.CLOUD_PREMIUM, HEURISTIC);

    ClassificationOutcome wrapped = decided.asFallbackFrom(
        ClassificationStrategy.EMBEDDING, FallbackCause.EMBEDDING_ERROR);

    assertEquals(ModelTier.CLOUD_PREMIUM, wrapped.tier());
    var fallback = assertInstanceOf(ClassificationJustification.Fallback.class,
        wrapped.justification());
    assertEquals(ClassificationStrategy.EMBEDDING, fallback.fallbackFrom());
    assertEquals(FallbackCause.EMBEDDING_ERROR, fallback.cause());
    assertEquals(HEURISTIC, fallback.effective());
    assertNull(fallback.evidence());
  }

  @Test
  void fallbackReportsTheStrategyThatDecidedNotTheOneThatSteppedAside() {
    var fallback = new ClassificationJustification.Fallback(
        ClassificationStrategy.LLM, FallbackCause.LLM_ERROR, HEURISTIC);

    assertEquals(ClassificationStrategy.HEURISTIC, fallback.strategy());
    assertEquals(ClassificationStrategy.LLM, fallback.fallbackFrom());
  }

  @Test
  void fallbackRequiresSomethingToHaveDecided() {
    // A fallback with nothing effective would be a FailSafe, not a fallback.
    assertThrows(IllegalArgumentException.class,
        () -> new ClassificationJustification.Fallback(
            ClassificationStrategy.LLM, FallbackCause.LLM_ERROR, null));
  }

  @Test
  void failSafeReportsTheStrategyThatFailed() {
    var failSafe = new ClassificationJustification.FailSafe(
        ClassificationStrategy.LLM, FallbackCause.NO_TIER_RETURNED);

    assertEquals(ClassificationStrategy.LLM, failSafe.strategy());
  }

  @Test
  void embeddingCandidatesAreDefensivelyCopied() {
    List<ClassificationJustification.RouteCandidate> mutable = new ArrayList<>();
    mutable.add(new ClassificationJustification.RouteCandidate(
        "chat", ModelTier.LOCAL, "hello", 0.8, 1));

    var embedding = new ClassificationJustification.Embedding(
        mutable, 0.8, 0.3, 0.6);
    mutable.clear();

    assertEquals(1, embedding.candidates().size());
  }

  @Test
  void embeddingToleratesNullCandidates() {
    var embedding =
        new ClassificationJustification.Embedding(null, 0, 0, 0.6);

    assertTrue(embedding.candidates().isEmpty());
    assertEquals(ClassificationStrategy.EMBEDDING, embedding.strategy());
  }

  @Test
  void heuristicFactoriesFillOnlyTheRelevantFields() {
    var keyword = ClassificationJustification.Heuristic.keyword("refactor");
    assertEquals(HeuristicRule.PREMIUM_KEYWORD, keyword.rule());
    assertEquals("refactor", keyword.matchedKeyword());
    assertNull(keyword.observedLength());

    var length = ClassificationJustification.Heuristic.length(
        HeuristicRule.ENTRY_LENGTH, 640, 500);
    assertEquals(640, length.observedLength());
    assertEquals(500, length.threshold());
    assertNull(length.matchedKeyword());
  }
}
