package io.github.yourimartin.gatewai.domain.model.calibration;

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

  @Test
  @DisplayName("a client calibration is a cache fit on shadow labels, naming its judge")
  void aClientCalibrationIsAShadowCacheFit() {
    ShadowFit fit = new ShadowFit("judge", "would-hit-v1", 0.95);

    ConformalCalibration client = shadow(CalibrationTarget.CACHE, "client-a", fit);
    assertFalse(client.isGlobal());
    assertEquals(fit, client.shadowFit());
    assertTrue(shadow(CalibrationTarget.CACHE, null, fit).isGlobal(),
        "a pooled shadow fit is the global row");

    assertThrows(IllegalArgumentException.class,
        () -> shadow(CalibrationTarget.ROUTING, null, fit),
        "routing is never fitted on judge labels");
    assertThrows(IllegalArgumentException.class,
        () -> shadow(CalibrationTarget.CACHE, "client-a", null),
        "a shadow fit names its judge, rubric and floor");
    assertThrows(IllegalArgumentException.class,
        () -> shadow(CalibrationTarget.CACHE, " ", fit));
    assertThrows(IllegalArgumentException.class, () -> new ConformalCalibration(
        CalibrationTarget.CACHE, ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.94, 93,
        "m", null, WHEN, "client-a", CalibrationSource.LABELLED_SET, null),
        "a client row only comes from that client's shadow labels");
    assertThrows(IllegalArgumentException.class, () -> new ConformalCalibration(
        CalibrationTarget.CACHE, ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.94, 93,
        "m", null, WHEN, null, CalibrationSource.LABELLED_SET, fit),
        "only a shadow fit carries a judge");
  }

  @Test
  void theShortConstructorIsAGlobalLabelledSetFit() {
    ConformalCalibration cache = calibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.94);

    assertTrue(cache.isGlobal());
    assertEquals(CalibrationSource.LABELLED_SET, cache.source());
    assertEquals(null, cache.shadowFit());
  }

  @Test
  void aShadowFitNamesItsJudgeAndRubricAndHasAFiniteFloor() {
    assertThrows(IllegalArgumentException.class,
        () -> new ShadowFit(" ", "would-hit-v1", 0.95));
    assertThrows(IllegalArgumentException.class, () -> new ShadowFit("judge", null, 0.95));
    assertThrows(IllegalArgumentException.class,
        () -> new ShadowFit("judge", "would-hit-v1", Double.NaN));
  }

  private static ConformalCalibration shadow(CalibrationTarget target, String clientId,
                                             ShadowFit fit) {
    return new ConformalCalibration(target, ConformalGuarantee.WRONG_ANSWER_RATE, 0.10,
        0.97, 50, "nomic-embed-text", null, WHEN, clientId, CalibrationSource.SHADOW, fit);
  }

  private static ConformalCalibration calibration(CalibrationTarget target,
                                                  ConformalGuarantee guarantee,
                                                  double alpha, double qhat) {
    return new ConformalCalibration(target, guarantee, alpha, qhat, 200,
        "nomic-embed-text",
        target == CalibrationTarget.ROUTING ? "cfg-1" : null, WHEN);
  }
}
