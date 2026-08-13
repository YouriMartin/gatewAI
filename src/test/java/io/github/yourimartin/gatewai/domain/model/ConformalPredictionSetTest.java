package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConformalPredictionSetTest {

  private static final double THRESHOLD = 0.60;
  private static final double BAND = 0.02;

  @Test
  @DisplayName("only routes above the threshold enter, and each tier once")
  void keepsDistinctTiersAboveTheThreshold() {
    ConformalPredictionSet set = ConformalPredictionSet.of(List.of(
        candidate(ModelTier.CLOUD_PREMIUM, 0.80, 1),
        candidate(ModelTier.CLOUD_PREMIUM, 0.75, 2),
        candidate(ModelTier.LOCAL, 0.61, 3),
        candidate(ModelTier.CLOUD_ENTRY, 0.40, 4)), calibrated());

    assertEquals(List.of(ModelTier.CLOUD_PREMIUM, ModelTier.LOCAL), set.tiers());
    assertEquals(ConformalStatus.AMBIGUOUS, set.status());
  }

  @Test
  @DisplayName("the set's shape is the status")
  void statusFollowsTheSize() {
    assertEquals(ConformalStatus.EMPTY_SET, ConformalPredictionSet.of(
        List.of(candidate(ModelTier.LOCAL, 0.10, 1)), calibrated()).status());
    assertEquals(ConformalStatus.SINGLETON, ConformalPredictionSet.of(
        List.of(candidate(ModelTier.LOCAL, 0.90, 1)), calibrated()).status());
  }

  @Test
  @DisplayName("an uncalibrated set still names the tiers, but claims nothing")
  void uncalibratedSetIsMarkedAsSuch() {
    ConformalPredictionSet set = ConformalPredictionSet.of(
        List.of(candidate(ModelTier.LOCAL, 0.90, 1)), uncalibrated());

    assertEquals(List.of(ModelTier.LOCAL), set.tiers());
    assertEquals(ConformalStatus.NOT_CALIBRATED, set.status());
  }

  @Test
  @DisplayName("a stale calibration is distinguishable from never having had one")
  void staleCalibrationIsItsOwnStatus() {
    CalibrationState stale = new CalibrationState(CalibrationTarget.ROUTING,
        CalibrationStatus.STALE, calibration(), THRESHOLD);

    assertEquals(ConformalStatus.STALE_CALIBRATION, ConformalPredictionSet.of(
        List.of(candidate(ModelTier.LOCAL, 0.90, 1)), stale).status());
  }

  @Test
  @DisplayName("nothing credible escalates; one tier decides")
  void emptyEscalatesAndSingletonDoesNot() {
    assertTrue(new ConformalPredictionSet(List.of(), ConformalStatus.EMPTY_SET)
        .escalates(0.50, BAND));
    assertFalse(new ConformalPredictionSet(List.of(ModelTier.LOCAL),
        ConformalStatus.SINGLETON).escalates(0.0, BAND));
  }

  @Test
  @DisplayName("several tiers escalate only when the top two are tied")
  void ambiguousEscalatesOnTheMarginOnly() {
    ConformalPredictionSet ambiguous = new ConformalPredictionSet(
        List.of(ModelTier.LOCAL, ModelTier.CLOUD_ENTRY), ConformalStatus.AMBIGUOUS);

    assertTrue(ambiguous.escalates(0.01, BAND));
    assertFalse(ambiguous.escalates(BAND, BAND), "the band is exclusive");
    assertFalse(ambiguous.escalates(0.20, BAND));
  }

  @Test
  @DisplayName("a zero band escalates on the empty set only")
  void aZeroBandTurnsTheModelLevelOffExceptWhenNothingIsCredible() {
    assertFalse(new ConformalPredictionSet(
        List.of(ModelTier.LOCAL, ModelTier.CLOUD_ENTRY), ConformalStatus.AMBIGUOUS)
        .escalates(0.0, 0.0));
    assertTrue(new ConformalPredictionSet(List.of(), ConformalStatus.EMPTY_SET)
        .escalates(0.0, 0.0));
  }

  @Test
  @DisplayName("candidates without a tier cannot enter the set")
  void untieredCandidatesAreIgnored() {
    ConformalPredictionSet set = ConformalPredictionSet.of(
        List.of(new ClassificationJustification.RouteCandidate(
            "broken", null, "example", 0.99, 1)), calibrated());

    assertEquals(List.of(), set.tiers());
  }

  private static ClassificationJustification.RouteCandidate candidate(
      ModelTier tier, double score, int rank) {
    return new ClassificationJustification.RouteCandidate(
        "route-" + rank, tier, "example", score, rank);
  }

  private static CalibrationState calibrated() {
    return new CalibrationState(CalibrationTarget.ROUTING,
        CalibrationStatus.VALID, calibration(), 0.99);
  }

  private static CalibrationState uncalibrated() {
    return new CalibrationState(CalibrationTarget.ROUTING,
        CalibrationStatus.ABSENT, null, THRESHOLD);
  }

  private static ConformalCalibration calibration() {
    return new ConformalCalibration(CalibrationTarget.ROUTING,
        ConformalGuarantee.CORRECT_TARGET_COVERAGE, 0.10, 1 - THRESHOLD, 200,
        "nomic-embed-text", "cfg1", Instant.parse("2026-08-13T00:00:00Z"));
  }
}
