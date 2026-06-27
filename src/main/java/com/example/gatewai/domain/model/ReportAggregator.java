package com.example.gatewai.domain.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates persisted {@link RequestLog} rows into a {@link GreenReport}
 * (Phase 4.5). Pure domain logic, zero framework dependencies.
 */
public final class ReportAggregator {

  /**
   * Sums per-request metrics and builds the model mix over the given rows.
   *
   * @param logs requests in the reporting range
   * @param from inclusive range start (echoed into the report)
   * @param to   inclusive range end (echoed into the report)
   * @return the aggregated report
   */
  public GreenReport aggregate(List<RequestLog> logs, Instant from, Instant to) {
    long cacheHits = 0;
    double costEur = 0.0;
    double costAvoidedEur = 0.0;
    double energyKwh = 0.0;
    double gramsCo2 = 0.0;
    double gramsCo2Avoided = 0.0;
    Map<String, Long> modelMix = new LinkedHashMap<>();

    for (RequestLog log : logs) {
      if (log.cacheHit()) {
        cacheHits++;
      }
      GreenMetrics green = log.green();
      if (green != null) {
        costEur += green.costEur();
        costAvoidedEur += green.costAvoidedEur();
        energyKwh += green.energyKwh();
        gramsCo2 += green.gramsCo2();
        gramsCo2Avoided += green.gramsCo2Avoided();
      }
      if (log.model() != null) {
        modelMix.merge(log.model(), 1L, Long::sum);
      }
    }

    return new GreenReport(from, to, logs.size(), cacheHits,
        costEur, costAvoidedEur, energyKwh, gramsCo2, gramsCo2Avoided, modelMix);
  }
}
