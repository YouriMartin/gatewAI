package io.github.yourimartin.gatewai.domain.model.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeVerdict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShadowLabelSelectionTest {

  private static final Instant EARLY = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant LATE = Instant.parse("2026-11-20T00:00:00Z");
  private static final ShadowLabelSelection.Criteria CRITERIA =
      new ShadowLabelSelection.Criteria("judge", "would-hit-v1", "model", 0.94);

  @Test
  @DisplayName("the floor keeps one population: recorded at or below it, scoring at or above")
  void theFloorCutsBothWays() {
    ShadowLabelSelection selection = ShadowLabelSelection.select(List.of(
        judged(JudgeVerdict.NO, 0.94, 0.94, EARLY), // on the floor: counts
        judged(JudgeVerdict.NO, 0.96, 0.92, LATE),  // recorded lower, cut at F: counts
        judged(JudgeVerdict.NO, 0.93, 0.92, LATE),  // below F
        judged(JudgeVerdict.NO, 0.99, 0.95, LATE)), // recorded above F
        0, CRITERIA);

    assertEquals(List.of(0.94, 0.96), selection.negatives());
    assertEquals(1, selection.exclusions().belowFloor());
    assertEquals(1, selection.exclusions().recordedAboveFloor());
    assertEquals(EARLY, selection.labelsFrom());
    assertEquals(LATE, selection.labelsTo());
  }

  @Test
  @DisplayName("a label left out is counted under the first rule it fails, once")
  void eachExclusionIsCountedOnce() {
    // Fails every rule: counted as FAILED only.
    ShadowLabel failsAll = new ShadowLabel("a", LATE, JudgeOutcome.FAILED, null, "other",
        "would-hit-v0", 0.50, 0.99, "other-model");
    // Judged UNSURE under another rubric: UNSURE comes first.
    ShadowLabel unsure = new ShadowLabel("a", LATE, JudgeOutcome.JUDGED, JudgeVerdict.UNSURE,
        "judge", "would-hit-v0", 0.95, 0.94, "model");

    ShadowLabelSelection selection =
        ShadowLabelSelection.select(List.of(failsAll, unsure), 3, CRITERIA);

    FitSummary.Exclusions exclusions = selection.exclusions();
    assertEquals(new FitSummary.Exclusions(3, 2, 1, 1, 0, 0, 0, 0, 0, 0), exclusions);
    assertEquals(exclusions.read(), exclusions.excluded());
  }

  @Test
  void positivesAndNegativesAreSplitAndCountedPerClient() {
    ShadowLabelSelection selection = ShadowLabelSelection.select(List.of(
        new ShadowLabel("b", LATE, JudgeOutcome.JUDGED, JudgeVerdict.YES, "judge",
            "would-hit-v1", 0.97, 0.94, "model"),
        new ShadowLabel("a", LATE, JudgeOutcome.JUDGED, JudgeVerdict.NO, "judge",
            "would-hit-v1", 0.95, 0.94, "model"),
        new ShadowLabel("a", LATE, JudgeOutcome.FAILED, null, "judge", "would-hit-v1",
            0.95, 0.94, "model")), 0, CRITERIA);

    assertEquals(List.of(0.95), selection.negatives());
    assertEquals(List.of(0.97), selection.positives());
    assertEquals(Map.of(
            "a", new FitSummary.ClientCounts(2, 0, 1),
            "b", new FitSummary.ClientCounts(1, 1, 0)),
        selection.perClient());
  }

  @Test
  void nothingCountedHasNoTimeSpan() {
    ShadowLabelSelection selection = ShadowLabelSelection.select(List.of(), 5, CRITERIA);

    assertNull(selection.labelsFrom());
    assertNull(selection.labelsTo());
    assertEquals(5, selection.exclusions().notSampled());
  }

  @Test
  void aWouldHitTheJudgeNeverSawIsNotALabel() {
    assertThrows(IllegalArgumentException.class, () -> new ShadowLabel("a", LATE,
        JudgeOutcome.NOT_SAMPLED, null, null, null, 0.95, 0.94, "model"));
  }

  private static ShadowLabel judged(JudgeVerdict verdict, double similarity,
                                    double recorded, Instant at) {
    return new ShadowLabel("a", at, JudgeOutcome.JUDGED, verdict, "judge", "would-hit-v1",
        similarity, recorded, "model");
  }
}
