package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregated green report over a date range (Phase 4.5): the figures a CSR / CSRD
 * team needs — money and CO2 saved, cache hit rate and model mix.
 *
 * <p>{@code excludedModelMix} carries the scope boundary (v3 lot C.1): requests
 * served by models whose energy is {@link EnergySource#NOT_ACCOUNTED} contribute
 * nothing to the emission totals, and every renderer uses {@link #emissionsScope()}
 * / {@link #scopeNote()} to say so rather than showing a bare zero.
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
 * @param excludedModelMix     model id → requests served by models excluded from
 *                             scope (subset of {@code modelMix}, cache hits aside)
 * @param breakdown            emissions by region and by provider, from what each
 *                             row stored about itself (v3 lot C.5)
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
    Map<String, Long> modelMix,
    Map<String, Long> excludedModelMix,
    EmissionsBreakdown breakdown
) {

  /**
   * What the emission figures are, in GHG-Protocol terms. Stated in every export:
   * hyperscalers report location-based and market-based figures that differ by an
   * order of magnitude, and publishing one without saying which is the same class of
   * error as omitting the region.
   */
  public static final String SCOPE_BASIS =
      "Location-based Scope 2 only (the physical grid that served each request). "
          + "Market-based accounting — net of renewable energy certificates and PPAs "
          + "— is not computed.";

  /**
   * Footnote required when emissions excluded from scope are shown next to a
   * non-zero avoided figure: the two are not on the same basis (see ADR 0006).
   */
  public static final String AVOIDED_BASIS_NOTE =
      "Avoided emissions are computed against the premium baseline, while the actual "
          + "emissions of models excluded from scope are not accounted at all. The two "
          + "figures are therefore not on the same basis and must not be netted.";

  public GreenReport {
    modelMix = modelMix == null ? Map.of() : Map.copyOf(modelMix);
    excludedModelMix =
        excludedModelMix == null ? Map.of() : Map.copyOf(excludedModelMix);
    breakdown = breakdown == null ? EmissionsBreakdown.EMPTY : breakdown;
  }

  /** Share of requests served from cache, in {@code [0, 1]}. */
  public double cacheHitRate() {
    return totalRequests == 0 ? 0.0 : (double) cacheHits / totalRequests;
  }

  /** Requests served by models whose energy is not accounted. */
  public long excludedRequests() {
    return excludedModelMix.values().stream().mapToLong(Long::longValue).sum();
  }

  /**
   * Inferences whose emissions are in the totals: everything that actually called
   * a model, minus the ones served by unaccounted models. Cache hits are excluded
   * because no inference happened, which is a genuine zero rather than a gap.
   */
  public long accountedRequests() {
    return Math.max(0L, totalRequests - cacheHits - excludedRequests());
  }

  /** Model ids excluded from scope, sorted for stable rendering. */
  public List<String> excludedModels() {
    return excludedModelMix.keySet().stream().sorted().toList();
  }

  /** How much of the period's inference the emission totals actually cover. */
  public EmissionsScope emissionsScope() {
    if (totalRequests == 0) {
      return EmissionsScope.NO_ACTIVITY;
    }
    if (excludedRequests() == 0) {
      return EmissionsScope.ALL_ACCOUNTED;
    }
    return accountedRequests() == 0
        ? EmissionsScope.ALL_EXCLUDED
        : EmissionsScope.PARTIALLY_EXCLUDED;
  }

  /**
   * One sentence stating what the emission totals cover — rendered verbatim by
   * the JSON, CSV, PDF and dashboard views so they cannot disagree.
   */
  public String scopeNote() {
    return switch (emissionsScope()) {
      case NO_ACTIVITY -> "No requests in this period.";
      case ALL_ACCOUNTED ->
          "All inference in this period was served by energy-accounted models "
              + "(location-based Scope 2).";
      case ALL_EXCLUDED ->
          "No emissions accounted: all " + excludedRequests() + " inference(s) were "
              + "served by models excluded from scope (" + joinExcluded() + "). The "
              + "CO2 total is zero because their energy is not accounted, not because "
              + "no energy was used.";
      case PARTIALLY_EXCLUDED ->
          excludedRequests() + " of " + (accountedRequests() + excludedRequests())
              + " inference(s) were served by models excluded from scope ("
              + joinExcluded() + "); their energy and emissions are not included in "
              + "the totals.";
    };
  }

  /** Whether {@link #AVOIDED_BASIS_NOTE} must be shown alongside the figures. */
  public boolean avoidedBasisDiffers() {
    return excludedRequests() > 0 && totalGramsCo2Avoided > 0.0;
  }

  /**
   * One sentence naming the regions that were assumed rather than known, or empty
   * when every region in the report is a fact (v3 lot C.5).
   */
  public String assumedRegionNote() {
    if (!breakdown.hasAssumedRegions()) {
      return "";
    }
    return "Region assumed, not known, for: "
        + String.join(", ", breakdown.assumedRegions())
        + ". The provider does not disclose which datacenter served each request; "
        + "these emissions are attributed to a grid the operator declared.";
  }

  private String joinExcluded() {
    return String.join(", ", excludedModels());
  }
}
