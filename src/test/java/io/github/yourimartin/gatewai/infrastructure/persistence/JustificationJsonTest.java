package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.CascadeLevel;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.FallbackCause;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.HeuristicRule;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.RouteCandidate;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.ModelTier;

import org.junit.jupiter.api.Test;

class JustificationJsonTest {

  @Test
  void heuristicKeywordRoundTrips() {
    var original = ClassificationJustification.Heuristic.keyword("refactor");

    assertEquals(original, roundTrip(original));
  }

  @Test
  void heuristicLengthRoundTripsWithItsNumbers() {
    var original = ClassificationJustification.Heuristic.length(
        HeuristicRule.PREMIUM_LENGTH, 640, 500);

    var restored = (ClassificationJustification.Heuristic) roundTrip(original);

    assertEquals(original, restored);
    assertEquals(640, restored.observedLength());
    assertEquals(500, restored.threshold());
  }

  @Test
  void heuristicWithoutValuesKeepsItsNulls() {
    var original =
        ClassificationJustification.Heuristic.of(HeuristicRule.CODE_FENCE);

    var restored = (ClassificationJustification.Heuristic) roundTrip(original);

    assertEquals(original, restored);
    assertNull(restored.matchedKeyword());
    assertNull(restored.observedLength());
  }

  @Test
  void embeddingRoundTripsEveryCandidate() {
    var original = new ClassificationJustification.Embedding(
        List.of(
            new RouteCandidate("code", ModelTier.CLOUD_PREMIUM,
                "refactor this service", 0.87, 1),
            new RouteCandidate("chat", ModelTier.LOCAL, "hello", 0.41, 2)),
        0.87, 0.46, 0.60);

    var restored = (ClassificationJustification.Embedding) roundTrip(original);

    assertEquals(original, restored);
    assertEquals("refactor this service",
        restored.candidates().getFirst().bestUtterance());
    assertEquals(0.46, restored.margin());
  }

  @Test
  void llmRoundTrips() {
    var original = new ClassificationJustification.Llm(
        "multi-step refactoring", "qwen2.5:1.5b");

    assertEquals(original, roundTrip(original));
  }

  @Test
  void fallbackRoundTripsBothItsNestedJustifications() {
    var original = new ClassificationJustification.Fallback(
        ClassificationStrategy.EMBEDDING, FallbackCause.BELOW_THRESHOLD,
        ClassificationJustification.Heuristic.keyword("debug"),
        new ClassificationJustification.Embedding(
            List.of(new RouteCandidate("chat", ModelTier.LOCAL, "hi", 0.4, 1)),
            0.4, 0.0, 0.6));

    var restored = (ClassificationJustification.Fallback) roundTrip(original);

    assertEquals(original, restored);
    assertEquals(ClassificationStrategy.HEURISTIC, restored.strategy());
    assertEquals(0.6, ((ClassificationJustification.Embedding)
        restored.evidence()).threshold());
  }

  @Test
  void fallbackWithoutEvidenceRoundTrips() {
    var original = new ClassificationJustification.Fallback(
        ClassificationStrategy.LLM, FallbackCause.LLM_ERROR,
        ClassificationJustification.Heuristic.of(HeuristicRule.DEFAULT));

    var restored = (ClassificationJustification.Fallback) roundTrip(original);

    assertEquals(original, restored);
    assertNull(restored.evidence());
  }

  @Test
  void failSafeRoundTrips() {
    var original = new ClassificationJustification.FailSafe(
        ClassificationStrategy.LLM, FallbackCause.NO_TIER_RETURNED);

    assertEquals(original, roundTrip(original));
  }

  @Test
  void nullsAreCarriedThroughBothWays() {
    assertNull(JustificationJson.toJson(null));
    assertNull(JustificationJson.fromJson(null));
    assertNull(JustificationJson.fromJson("  "));
  }

  @Test
  void storedJsonNamesItsVariant() {
    // The discriminator is what makes the column readable in psql and what the
    // explain API will switch on.
    String json = JustificationJson.toJson(
        ClassificationJustification.Heuristic.keyword("debug"));

    assertTrue(json.contains("\"type\":\"heuristic\""), json);
    assertTrue(json.contains("\"matchedKeyword\":\"debug\""), json);
  }

  @Test
  void cascadeRoundTripsWithTheEvidenceItEscalatedOn() {
    var original = new ClassificationJustification.Cascade(
        CascadeLevel.LLM, 0.02,
        new ClassificationJustification.Llm("multi-step", "qwen2.5:1.5b"),
        new ClassificationJustification.Embedding(List.of(
            new ClassificationJustification.RouteCandidate(
                "casual-chat", ModelTier.LOCAL, "hello", 0.71, 1)),
            0.71, 0.01, 0.60));

    assertEquals(original, roundTrip(original));
  }

  @Test
  void cascadeThatDecidedEarlyHasNoEvidence() {
    var original = new ClassificationJustification.Cascade(
        CascadeLevel.DETERMINISTIC, 0.02,
        ClassificationJustification.Heuristic.of(HeuristicRule.CODE_FENCE),
        null);

    var restored = (ClassificationJustification.Cascade) roundTrip(original);

    assertEquals(original, restored);
    assertNull(restored.escalatedOn());
  }

  private static ClassificationJustification roundTrip(
      ClassificationJustification justification) {
    return JustificationJson.fromJson(JustificationJson.toJson(justification));
  }
}
