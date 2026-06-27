package com.example.gatewai.domain.model;

/**
 * Computes per-request {@link GreenMetrics} (Phase 4.3): cost, energy, emissions
 * and CO2 avoided versus a premium-by-default baseline.
 *
 * <p>Pure domain logic composing the {@link CarbonCalculator}. The "avoided"
 * figure is {@code emission(premium baseline) − emission(actual model)} for the
 * same token usage: it credits the carbon saved by routing a request to a
 * cheaper/greener model instead of the most capable one.
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
   * @param used                     the model that actually served the request
   * @param premiumBaseline          the premium-default model, or {@code null}
   * @param totalTokens              total tokens billed (prompt + completion)
   * @param gridIntensityGramsPerKwh grid carbon intensity, in gCO2/kWh
   * @return the metrics, never {@code null}; {@link GreenMetrics#ZERO} when the
   *     used model is unknown or no tokens were consumed
   */
  public GreenMetrics account(ModelDefinition used,
                              ModelDefinition premiumBaseline,
                              long totalTokens,
                              double gridIntensityGramsPerKwh) {
    if (used == null || totalTokens <= 0) {
      return GreenMetrics.ZERO;
    }

    double costEur = (totalTokens / TOKENS_PER_UNIT) * used.costPer1kTokens();
    CarbonFootprint actual =
        carbonCalculator.estimate(used, totalTokens, gridIntensityGramsPerKwh);

    double gramsCo2Avoided = 0.0;
    if (premiumBaseline != null) {
      CarbonFootprint baseline = carbonCalculator.estimate(
          premiumBaseline, totalTokens, gridIntensityGramsPerKwh);
      gramsCo2Avoided = Math.max(0.0, baseline.gramsCo2() - actual.gramsCo2());
    }

    return new GreenMetrics(
        costEur, actual.energyKwh(), actual.gramsCo2(), gramsCo2Avoided);
  }
}
