package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.TreeMap;

import io.github.yourimartin.gatewai.domain.model.calibration.FitSummary;

import org.junit.jupiter.api.Test;

class FitSummaryJsonTest {

  @Test
  void aShadowSummaryRoundTrips() {
    TreeMap<String, FitSummary.ClientCounts> perClient = new TreeMap<>();
    perClient.put("a", new FitSummary.ClientCounts(13, 2, 3));
    perClient.put("b", new FitSummary.ClientCounts(4, 4, 0));
    FitSummary.Shadow summary = new FitSummary.Shadow(
        new FitSummary.Exclusions(7, 17, 1, 2, 3, 4, 5, 6, 7, 8), 6, 3,
        Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-20T10:00:00Z"),
        0.5, 2, 0.535841, 200, 50, perClient);

    assertEquals(summary, FitSummaryJson.fromJson(FitSummaryJson.toJson(summary)));
  }

  @Test
  void theOptionalFieldsRoundTripAsAbsent() {
    FitSummary.Shadow summary = new FitSummary.Shadow(
        new FitSummary.Exclusions(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0, 0, null, null, null,
        0, 0.2, 200, 50, new TreeMap<>());

    assertEquals(summary, FitSummaryJson.fromJson(FitSummaryJson.toJson(summary)));
  }

  @Test
  void aLabelledSetSummaryRoundTripsAndStatesItsVersion() {
    FitSummary.LabelledSet summary =
        new FitSummary.LabelledSet("classpath:/eval/cache-calibration.jsonl", 200, 93);
    String json = FitSummaryJson.toJson(summary);

    assertTrue(json.contains("\"version\":" + FitSummaryJson.VERSION), json);
    assertEquals(summary, FitSummaryJson.fromJson(json));
  }

  @Test
  void anUnknownShapeReadsAsNoSummaryRatherThanFailingItsCalibration() {
    assertNull(FitSummaryJson.fromJson(null));
    assertNull(FitSummaryJson.fromJson("{\"version\":99,\"type\":\"shadow\"}"));
    assertNull(FitSummaryJson.fromJson("{\"version\":1,\"type\":\"guessed\"}"));
    assertNull(FitSummaryJson.toJson(null));
  }
}
