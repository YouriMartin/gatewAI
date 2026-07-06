package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * Aggregated green report over a date range (Phase 4.5): the figures a CSR / CSRD
 * team needs — money and CO2 saved, cache hit rate and model mix.
 *
 * @param from                 inclusive start of the range
 * @param to                   inclusive end of the range
 * @param totalRequests        number of requests served in the range
 * @param cacheHits            requests answered from the semantic cache
 * @param totalCostEur         total monetary cost actually incurred
 * @param totalCostAvoidedEur  money saved vs a premium-default baseline
 * @param totalEnergyKwh       total estimated energy consumed
 * @param totalGramsCo2        total estimated emissions actually produced
 * @param totalGramsCo2Avoided total emissions avoided vs a premium baseline
 * @param modelMix             model id → number of requests it served
 */
public record GreenReport(
    Instant from,
    Instant to,
    long totalRequests,
    long cacheHits,
    double totalCostEur,
    double totalCostAvoidedEur,
    double totalEnergyKwh,
    double totalGramsCo2,
    double totalGramsCo2Avoided,
    Map<String, Long> modelMix
) {

  public GreenReport {
    modelMix = modelMix == null ? Map.of() : Map.copyOf(modelMix);
  }

  /** Share of requests served from cache, in {@code [0, 1]}. */
  public double cacheHitRate() {
    return totalRequests == 0 ? 0.0 : (double) cacheHits / totalRequests;
  }
}
