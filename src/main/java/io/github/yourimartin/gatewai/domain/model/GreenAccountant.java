package io.github.yourimartin.gatewai.domain.model;

/**
 * Computes per-request {@link GreenMetrics} (Phase 4.3): cost, energy, emissions
 * and CO2 avoided versus a premium-by-default baseline.
 *
 * <p>Pure domain logic composing the {@link CarbonCalculator}. The "avoided" figure
 * is {@code emission(premium baseline) − emission(actual model)} for the same token
 * usage: it credits the carbon saved by routing a request to a cheaper/greener model
 * instead of the most capable one.
 *
 * <p>Each side is a {@link ModelSite} — a model <em>plus</em> the grid and the
 * datacenter it ran on (v3 lot C.3). The premium baseline is a counterfactual about
 * the premium provider's datacenter, so it is priced there, not at the grid of
 * whatever answered. When both sit behind the same provider the two sites are equal.
 */
public final class GreenAccountant {

  private static final double TOKENS_PER_UNIT = 1000.0;

  private final CarbonCalculator carbonCalculator;

  public GreenAccountant(CarbonCalculator carbonCalculator) {
    this.carbonCalculator = carbonCalculator;
  }

  /**
   * Accounts for a single served request.
   *
   * <p>On a cache hit no inference happens: real cost, energy and emissions are
   * zero, and the whole premium-default call is credited as avoided. This is the
   * cache's headline saving, which would otherwise be invisible.
   *
   * @param used     the model that served the request and where it ran; its model
   *                 may be {@code null} (ignored on a cache hit)
   * @param baseline the premium-default model and where it would have run, or
   *                 {@code null}
   * @param usage    token counts as the provider reported them
   * @param cacheHit whether the response was served from cache
   * @return the metrics, never {@code null}; {@link GreenMetrics#ZERO} when no
   *     tokens were consumed or the used model is unknown (on a miss)
   */
  public GreenMetrics account(ModelSite used,
                              ModelSite baseline,
                              TokenUsage usage,
                              boolean cacheHit) {
    if (usage == null || usage.totalTokens() <= 0) {
      return GreenMetrics.ZERO;
    }
    ModelDefinition baselineModel = baseline == null ? null : baseline.model();

    if (cacheHit) {
      double avoidedCo2 = carbonCalculator.estimate(baseline, usage).gramsCo2();
      double avoidedCost = baselineModel == null
          ? 0.0 : costOf(baselineModel, usage.totalTokens());
      return new GreenMetrics(0.0, 0.0, 0.0, avoidedCost, avoidedCo2);
    }

    if (used == null || used.model() == null) {
      return GreenMetrics.ZERO;
    }

    double costEur = costOf(used.model(), usage.totalTokens());
    CarbonFootprint actual = carbonCalculator.estimate(used, usage);

    double gramsCo2Avoided = 0.0;
    double costAvoidedEur = 0.0;
    if (baselineModel != null) {
      CarbonFootprint baselineFootprint = carbonCalculator.estimate(baseline, usage);
      gramsCo2Avoided =
          Math.max(0.0, baselineFootprint.gramsCo2() - actual.gramsCo2());
      costAvoidedEur = Math.max(0.0,
          costOf(baselineModel, usage.totalTokens()) - costEur);
    }

    return new GreenMetrics(costEur, actual.energyKwh(), actual.gramsCo2(),
        costAvoidedEur, gramsCo2Avoided);
  }

  private static double costOf(ModelDefinition model, long totalTokens) {
    return (totalTokens / TOKENS_PER_UNIT) * model.costPer1kTokens();
  }
}
