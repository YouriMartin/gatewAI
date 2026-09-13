package io.github.yourimartin.gatewai.domain.model;

/**
 * Computes per-request {@link GreenMetrics} (Phase 4.3): cost, energy, emissions
 * and CO2 avoided versus a premium-by-default baseline.
 *
 * <p>Pure domain logic composing the {@link CarbonCalculator}. The "avoided"
 * figure is {@code emission(premium baseline) − emission(actual model)} for the
 * same token usage: it credits the carbon saved by routing a request to a
 * cheaper/greener model instead of the most capable one.
 *
 * <p>The two sides take <b>their own grid intensity</b> (v3 lot C.3). The premium
 * baseline is a counterfactual that would have run on the premium provider's
 * hardware, in the premium provider's region — pricing it at the served model's
 * grid is the same wrong-grid error the lot exists to fix, one step removed. When
 * both models sit behind the same provider the two values are simply equal.
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
   * @param used                     the model that served the request, or
   *                                 {@code null} (ignored on a cache hit)
   * @param premiumBaseline          the premium-default model, or {@code null}
   * @param totalTokens              total tokens billed (prompt + completion)
   * @param usedIntensityGramsPerKwh grid carbon intensity where the served model
   *                                 ran, in gCO2/kWh
   * @param baselineIntensityGramsPerKwh grid carbon intensity where the premium
   *                                 baseline <em>would</em> have run
   * @param cacheHit                 whether the response was served from cache
   * @return the metrics, never {@code null}; {@link GreenMetrics#ZERO} when no
   *     tokens were consumed or the used model is unknown (on a miss)
   */
  public GreenMetrics account(ModelDefinition used,
                              ModelDefinition premiumBaseline,
                              long totalTokens,
                              double usedIntensityGramsPerKwh,
                              double baselineIntensityGramsPerKwh,
                              boolean cacheHit) {
    if (totalTokens <= 0) {
      return GreenMetrics.ZERO;
    }

    if (cacheHit) {
      double avoidedCo2 = premiumBaseline == null ? 0.0 : carbonCalculator
          .estimate(premiumBaseline, totalTokens, baselineIntensityGramsPerKwh)
          .gramsCo2();
      double avoidedCost =
          premiumBaseline == null ? 0.0 : costOf(premiumBaseline, totalTokens);
      return new GreenMetrics(0.0, 0.0, 0.0, avoidedCost, avoidedCo2);
    }

    if (used == null) {
      return GreenMetrics.ZERO;
    }

    double costEur = costOf(used, totalTokens);
    CarbonFootprint actual =
        carbonCalculator.estimate(used, totalTokens, usedIntensityGramsPerKwh);

    double gramsCo2Avoided = 0.0;
    double costAvoidedEur = 0.0;
    if (premiumBaseline != null) {
      CarbonFootprint baseline = carbonCalculator.estimate(
          premiumBaseline, totalTokens, baselineIntensityGramsPerKwh);
      gramsCo2Avoided = Math.max(0.0, baseline.gramsCo2() - actual.gramsCo2());
      costAvoidedEur =
          Math.max(0.0, costOf(premiumBaseline, totalTokens) - costEur);
    }

    return new GreenMetrics(costEur, actual.energyKwh(), actual.gramsCo2(),
        costAvoidedEur, gramsCo2Avoided);
  }

  /**
   * Both sides at the same grid intensity — the pre-C.3 behaviour, kept for the
   * cases where one zone genuinely covers both (same provider, or a caller that
   * has no region information at all).
   */
  public GreenMetrics account(ModelDefinition used,
                              ModelDefinition premiumBaseline,
                              long totalTokens,
                              double gridIntensityGramsPerKwh,
                              boolean cacheHit) {
    return account(used, premiumBaseline, totalTokens,
        gridIntensityGramsPerKwh, gridIntensityGramsPerKwh, cacheHit);
  }

  private static double costOf(ModelDefinition model, long totalTokens) {
    return (totalTokens / TOKENS_PER_UNIT) * model.costPer1kTokens();
  }
}
