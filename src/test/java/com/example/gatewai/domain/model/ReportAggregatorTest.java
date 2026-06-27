package com.example.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReportAggregatorTest {

  private static final double DELTA = 1e-9;
  private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-06-30T23:59:59Z");

  private final ReportAggregator aggregator = new ReportAggregator();

  private static RequestLog log(String model, double cost, double costAvoided,
                                double co2, double co2Avoided, boolean cacheHit) {
    return new RequestLog(
        UUID.randomUUID(), Instant.now(), model, "hash", 1, 1, 2, 0L, "client",
        new GreenMetrics(cost, 0.001, co2, costAvoided, co2Avoided), cacheHit);
  }

  @Test
  void aggregatesTotalsCacheRateAndModelMix() {
    List<RequestLog> logs = List.of(
        log("haiku", 0.002, 0.013, 0.46, 0.69, false),
        log("sonnet", 0.015, 0.0, 1.15, 0.0, false),
        log("sonnet", 0.0, 0.015, 0.0, 1.15, true));

    GreenReport report = aggregator.aggregate(logs, FROM, TO);

    assertEquals(FROM, report.from());
    assertEquals(TO, report.to());
    assertEquals(3, report.totalRequests());
    assertEquals(1, report.cacheHits());
    assertEquals(1.0 / 3, report.cacheHitRate(), DELTA);
    assertEquals(0.017, report.totalCostEur(), DELTA);
    assertEquals(0.028, report.totalCostAvoidedEur(), DELTA);
    assertEquals(0.003, report.totalEnergyKwh(), DELTA);
    assertEquals(1.61, report.totalGramsCo2(), DELTA);
    assertEquals(1.84, report.totalGramsCo2Avoided(), DELTA);
    assertEquals(Map.of("haiku", 1L, "sonnet", 2L), report.modelMix());
  }

  @Test
  void emptyLogsProduceZeroReport() {
    GreenReport report = aggregator.aggregate(List.of(), FROM, TO);

    assertEquals(0, report.totalRequests());
    assertEquals(0.0, report.cacheHitRate(), DELTA);
    assertEquals(0.0, report.totalGramsCo2Avoided(), DELTA);
    assertTrue(report.modelMix().isEmpty());
  }
}
