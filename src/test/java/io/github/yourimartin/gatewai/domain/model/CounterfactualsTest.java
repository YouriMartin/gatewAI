package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.RouteCandidate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counterfactual selection on hand-written rankings: no embedding, so every
 * expected gap is a subtraction the reader can check.
 */
class CounterfactualsTest {

  @Test
  @DisplayName("the near misses come back closest first, with their gaps")
  void ranksAlternativesByGap() {
    List<Counterfactual> alternatives = Counterfactuals.from(List.of(
        candidate("casual-chat", ModelTier.LOCAL, "Hello there", 0.80, 1),
        candidate("drafting", ModelTier.CLOUD_ENTRY, "Summarize this", 0.75, 2),
        candidate("code", ModelTier.CLOUD_PREMIUM, "Refactor this", 0.50, 3)), 3);

    assertEquals(2, alternatives.size());

    Counterfactual first = alternatives.getFirst();
    assertEquals(ModelTier.CLOUD_ENTRY, first.tier());
    assertEquals("Summarize this", first.nearestUtterance());
    assertEquals(0.05, first.gap(), 1e-9);
    assertEquals(1, first.rank());

    assertEquals(0.30, alternatives.get(1).gap(), 1e-9);
    assertEquals(2, alternatives.get(1).rank());
  }

  @Test
  @DisplayName("a route leading back to the chosen tier is not a counterfactual")
  void excludesTheChosenTier() {
    List<Counterfactual> alternatives = Counterfactuals.from(List.of(
        candidate("casual-chat", ModelTier.LOCAL, "Hello there", 0.80, 1),
        candidate("small-talk", ModelTier.LOCAL, "How are you", 0.79, 2),
        candidate("code", ModelTier.CLOUD_PREMIUM, "Refactor this", 0.40, 3)), 3);

    assertEquals(1, alternatives.size());
    assertEquals(ModelTier.CLOUD_PREMIUM, alternatives.getFirst().tier());
  }

  @Test
  @DisplayName("one alternative per tier: the best route is the one that would win")
  void keepsTheBestRoutePerTier() {
    List<Counterfactual> alternatives = Counterfactuals.from(List.of(
        candidate("casual-chat", ModelTier.LOCAL, "Hello there", 0.80, 1),
        candidate("code", ModelTier.CLOUD_PREMIUM, "Refactor this", 0.70, 2),
        candidate("analysis", ModelTier.CLOUD_PREMIUM, "Prove this", 0.60, 3)), 3);

    assertEquals(1, alternatives.size());
    assertEquals("code", alternatives.getFirst().route());
  }

  @Test
  @DisplayName("every route leading to the winning tier means no alternative at all")
  void singleTierYieldsNothing() {
    assertTrue(Counterfactuals.from(List.of(
        candidate("casual-chat", ModelTier.LOCAL, "Hello there", 0.80, 1),
        candidate("small-talk", ModelTier.LOCAL, "How are you", 0.70, 2)), 3)
        .isEmpty());
  }

  @Test
  @DisplayName("the limit bounds what is returned")
  void respectsTheLimit() {
    List<RouteCandidate> ranked = List.of(
        candidate("casual-chat", ModelTier.LOCAL, "Hello there", 0.80, 1),
        candidate("drafting", ModelTier.CLOUD_ENTRY, "Summarize this", 0.70, 2),
        candidate("code", ModelTier.CLOUD_PREMIUM, "Refactor this", 0.60, 3));

    assertEquals(1, Counterfactuals.from(ranked, 1).size());
    assertTrue(Counterfactuals.from(ranked, 0).isEmpty());
  }

  @Test
  @DisplayName("nothing ranked, nothing missed")
  void emptyRanking() {
    assertTrue(Counterfactuals.from(List.of(), 3).isEmpty());
    assertTrue(Counterfactuals.from(null, 3).isEmpty());
  }

  @Test
  @DisplayName("equal scores are a gap of zero, never a negative one")
  void gapsAreNeverNegative() {
    List<Counterfactual> alternatives = Counterfactuals.from(List.of(
        candidate("casual-chat", ModelTier.LOCAL, "Hello there", 0.80, 1),
        candidate("drafting", ModelTier.CLOUD_ENTRY, "Summarize this", 0.80, 2)),
        3);

    assertEquals(0.0, alternatives.getFirst().gap(), 1e-9);
  }

  private static RouteCandidate candidate(String route, ModelTier tier,
                                          String utterance, double score,
                                          int rank) {
    return new RouteCandidate(route, tier, utterance, score, rank);
  }
}
