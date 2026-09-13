package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The scope boundary a report derives from its model mix (v3 lot C.1). */
class GreenReportTest {

  private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-06-30T00:00:00Z");

  private static GreenReport report(long requests, long cacheHits,
                                    double gramsCo2, double gramsCo2Avoided,
                                    Map<String, Long> modelMix,
                                    Map<String, Long> excluded) {
    return new GreenReport(FROM, TO, requests, cacheHits, 0.0, 0.0, 0.0,
        gramsCo2, gramsCo2Avoided, modelMix, excluded);
  }

  @Test
  void noRequestsMeansNoActivityRatherThanAnEmptyClaim() {
    GreenReport report = report(0, 0, 0.0, 0.0, Map.of(), Map.of());

    assertEquals(EmissionsScope.NO_ACTIVITY, report.emissionsScope());
    assertEquals("No requests in this period.", report.scopeNote());
    assertFalse(report.avoidedBasisDiffers());
  }

  @Test
  void everythingAccountedIsStatedAsSuch() {
    GreenReport report = report(2, 0, 1.2, 0.4,
        Map.of("claude-opus-4-8", 2L), Map.of());

    assertEquals(EmissionsScope.ALL_ACCOUNTED, report.emissionsScope());
    assertEquals(2, report.accountedRequests());
    assertEquals(0, report.excludedRequests());
    assertTrue(report.scopeNote().contains("energy-accounted"), report.scopeNote());
    assertFalse(report.avoidedBasisDiffers());
  }

  @Test
  void allLocalReportExplainsWhyTheZeroIsZero() {
    GreenReport report = report(4, 1, 0.0, 0.0,
        Map.of("qwen2.5:3b", 4L), Map.of("qwen2.5:3b", 3L));

    assertEquals(EmissionsScope.ALL_EXCLUDED, report.emissionsScope());
    assertEquals(3, report.excludedRequests());
    assertEquals(0, report.accountedRequests());
    assertEquals(List.of("qwen2.5:3b"), report.excludedModels());
    assertTrue(report.scopeNote().contains("excluded from scope"), report.scopeNote());
    assertTrue(report.scopeNote().contains("not because no energy was used"),
        report.scopeNote());
  }

  @Test
  void mixedReportCountsBothSidesAndFlagsTheAvoidedBasis() {
    GreenReport report = report(4, 0, 1.2, 2.4,
        Map.of("claude-opus-4-8", 2L, "qwen2.5:3b", 2L), Map.of("qwen2.5:3b", 2L));

    assertEquals(EmissionsScope.PARTIALLY_EXCLUDED, report.emissionsScope());
    assertEquals(2, report.accountedRequests());
    assertEquals(2, report.excludedRequests());
    assertTrue(report.scopeNote().contains("2 of 4 inference(s)"), report.scopeNote());
    // An excluded actual next to a non-zero avoided figure needs the footnote.
    assertTrue(report.avoidedBasisDiffers());
  }

  @Test
  void excludedModelsAreSortedForStableRendering() {
    GreenReport report = report(3, 0, 0.0, 0.0,
        Map.of("zeta", 1L, "alpha", 1L, "mid", 1L),
        Map.of("zeta", 1L, "alpha", 1L, "mid", 1L));

    assertEquals(List.of("alpha", "mid", "zeta"), report.excludedModels());
  }

  @Test
  void nullMapsAreTreatedAsEmpty() {
    GreenReport report = new GreenReport(FROM, TO, 0, 0, 0, 0, 0, 0, 0, null, null);

    assertTrue(report.modelMix().isEmpty());
    assertTrue(report.excludedModelMix().isEmpty());
    assertEquals(0, report.excludedRequests());
  }
}
