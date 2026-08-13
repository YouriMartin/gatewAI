package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.HeuristicRule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one place route scores are dug out of a justification (v2 batch 6). Four
 * variants can carry them and every reader needs all four, so the cases are
 * pinned here rather than re-derived by each caller.
 */
class ClassificationJustificationTest {

  private static final ClassificationJustification.Embedding SCORES =
      new ClassificationJustification.Embedding(List.of(
          new ClassificationJustification.RouteCandidate(
              "casual-chat", ModelTier.LOCAL, "hello", 0.71, 1)),
          0.71, 0.05, 0.60);

  @Test
  @DisplayName("the embedding strategy's own decision carries them")
  void embeddingDecision() {
    assertSame(SCORES, ClassificationJustification.routeScores(SCORES).orElseThrow());
  }

  @Test
  @DisplayName("a below-threshold hand-over carries them as evidence")
  void handOverEvidence() {
    ClassificationJustification handedOver =
        new ClassificationJustification.Fallback(
            ClassificationStrategy.EMBEDDING,
            ClassificationJustification.FallbackCause.BELOW_THRESHOLD,
            ClassificationJustification.Heuristic.of(HeuristicRule.DEFAULT),
            SCORES);

    assertSame(SCORES,
        ClassificationJustification.routeScores(handedOver).orElseThrow());
  }

  @Test
  @DisplayName("a cascade that stopped at the routes carries them in its decision")
  void cascadeThatDecidedOnTheRoutes() {
    ClassificationJustification cascade = new ClassificationJustification.Cascade(
        CascadeLevel.EMBEDDING, 0.02, SCORES, null);

    assertSame(SCORES,
        ClassificationJustification.routeScores(cascade).orElseThrow());
  }

  @Test
  @DisplayName("a cascade that escalated carries the scores it escalated on")
  void cascadeThatEscalated() {
    ClassificationJustification cascade = new ClassificationJustification.Cascade(
        CascadeLevel.LLM, 0.02,
        new ClassificationJustification.Llm("multi-step", "model-x"), SCORES);

    assertSame(SCORES,
        ClassificationJustification.routeScores(cascade).orElseThrow());
  }

  @Test
  @DisplayName("no embedding ran: empty, never a zero-scored stand-in")
  void nothingToReport() {
    assertTrue(ClassificationJustification.routeScores(null).isEmpty());
    assertTrue(ClassificationJustification.routeScores(
        ClassificationJustification.Heuristic.keyword("refactor")).isEmpty());
    assertTrue(ClassificationJustification.routeScores(
        new ClassificationJustification.FailSafe(ClassificationStrategy.LLM,
            ClassificationJustification.FallbackCause.LLM_ERROR)).isEmpty());
    assertTrue(ClassificationJustification.routeScores(
        new ClassificationJustification.Fallback(ClassificationStrategy.LLM,
            ClassificationJustification.FallbackCause.LLM_ERROR,
            ClassificationJustification.Heuristic.of(HeuristicRule.DEFAULT)))
        .isEmpty());
  }

  @Test
  @DisplayName("the margin is what readers actually want out of them")
  void marginIsReachable() {
    Optional<Double> margin = ClassificationJustification.routeScores(SCORES)
        .map(ClassificationJustification.Embedding::margin);

    assertEquals(0.05, margin.orElseThrow(), 1e-9);
  }
}
