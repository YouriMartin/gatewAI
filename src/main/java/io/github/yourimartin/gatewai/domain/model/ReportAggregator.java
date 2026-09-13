package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates persisted {@link RequestLog} rows into a {@link GreenReport}
 * (Phase 4.5). Pure domain logic, zero framework dependencies.
 *
 * <p>Besides the totals it tracks the <b>scope boundary</b> (v3 lot C.1): rows
 * served by a model whose energy is {@link EnergySource#NOT_ACCOUNTED} are
 * counted separately, so a report can state that its zero means "excluded"
 * rather than "measured zero". Cache hits are never counted as excluded — no
 * inference ran, which is a genuine zero.
 */
public final class ReportAggregator {

  private final EnergySourceLookup energySources;

  /**
   * @param energySources resolves the energy provenance of a served model id
   */
  public ReportAggregator(EnergySourceLookup energySources) {
    this.energySources = Objects.requireNonNull(energySources, "energySources");
  }

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
    Map<String, Long> excludedModelMix = new LinkedHashMap<>();

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
        if (!log.cacheHit() && !energySources.forModelId(log.model()).accounted()) {
          excludedModelMix.merge(log.model(), 1L, Long::sum);
        }
      }
    }

    return new GreenReport(from, to, logs.size(), cacheHits,
        costEur, costAvoidedEur, energyKwh, gramsCo2, gramsCo2Avoided, modelMix,
        excludedModelMix);
  }

  /**
   * One {@link GreenReport} per UTC day in {@code [from, to)} (empty days
   * included, so charts have continuous points).
   */
  public List<GreenReport> aggregateDaily(List<RequestLog> logs,
                                          Instant from, Instant to) {
    Map<Instant, List<RequestLog>> byDay = new HashMap<>();
    for (RequestLog log : logs) {
      Instant day = log.timestamp().truncatedTo(ChronoUnit.DAYS);
      byDay.computeIfAbsent(day, d -> new ArrayList<>()).add(log);
    }

    List<GreenReport> series = new ArrayList<>();
    Instant day = from.truncatedTo(ChronoUnit.DAYS);
    while (day.isBefore(to)) {
      Instant next = day.plus(1, ChronoUnit.DAYS);
      series.add(aggregate(byDay.getOrDefault(day, List.of()), day, next));
      day = next;
    }
    return series;
  }
}
