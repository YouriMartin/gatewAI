package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConformalCalibrationTest {

  private static final Instant WHEN = Instant.parse("2026-08-11T10:00:00Z");

  @Test
  @DisplayName("the two guarantees map their quantile to a threshold from opposite ends")
  void thresholdDependsOnTheGuarantee() {
    ConformalCalibration coverage = calibration(CalibrationTarget.ROUTING,
        ConformalGuarantee.CORRECT_TARGET_COVERAGE, 0.10, 0.54);
    ConformalCalibration wrongAnswers = calibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.94);

    // Coverage scores 1 - similarity on the correct target: admit above 1 - qhat.
    assertEquals(0.46, coverage.similarityThreshold(), 1e-9);
    // The wrong-answer rate scores the similarity of pairs that must not be
    // served: admit only above where those pairs stop.
    assertEquals(0.94, wrongAnswers.similarityThreshold(), 1e-9);

    assertTrue(coverage.admits(0.46));
    assertFalse(coverage.admits(0.45));
    assertTrue(wrongAnswers.admits(0.99));
    assertFalse(wrongAnswers.admits(0.93));
  }

  @Test
  void aChangedEmbeddingModelMakesAnyCalibrationStale() {
    ConformalCalibration routing = calibration(CalibrationTarget.ROUTING,
        ConformalGuarantee.CORRECT_TARGET_COVERAGE, 0.10, 0.54);

    assertEquals(CalibrationStatus.VALID,
        routing.statusFor("nomic-embed-text", "cfg-1"));
    assertEquals(CalibrationStatus.STALE,
        routing.statusFor("mxbai-embed-large", "cfg-1"));
  }

  @Test
  @DisplayName("a route edit invalidates routing but not the cache")
  void routingConfigOnlyAffectsRouting() {
    ConformalCalibration routing = calibration(CalibrationTarget.ROUTING,
        ConformalGuarantee.CORRECT_TARGET_COVERAGE, 0.10, 0.54);
    ConformalCalibration cache = calibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.94);

    assertEquals(CalibrationStatus.STALE,
        routing.statusFor("nomic-embed-text", "cfg-2"),
        "routing similarities are measured against the route examples themselves");
    assertEquals(CalibrationStatus.VALID,
        cache.statusFor("nomic-embed-text", "cfg-2"),
        "a cache pair's similarity has nothing to do with the routes");
  }

  @Test
  void rejectsAnImpossibleAlphaOrSample() {
    assertThrows(IllegalArgumentException.class, () -> new ConformalCalibration(
        CalibrationTarget.CACHE, ConformalGuarantee.WRONG_ANSWER_RATE,
        1.5, 0.9, 200, "m", null, WHEN));
    assertThrows(IllegalArgumentException.class, () -> new ConformalCalibration(
        CalibrationTarget.CACHE, ConformalGuarantee.WRONG_ANSWER_RATE,
        0.1, 0.9, 0, "m", null, WHEN));
  }

  private static ConformalCalibration calibration(CalibrationTarget target,
                                                  ConformalGuarantee guarantee,
                                                  double alpha, double qhat) {
    return new ConformalCalibration(target, guarantee, alpha, qhat, 200,
        "nomic-embed-text",
        target == CalibrationTarget.ROUTING ? "cfg-1" : null, WHEN);
  }
}
