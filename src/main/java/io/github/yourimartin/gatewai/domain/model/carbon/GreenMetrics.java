package io.github.yourimartin.gatewai.domain.model.carbon;

import io.github.yourimartin.gatewai.domain.model.llm.PricingBasis;

/**
 * Per-request green accounting: monetary cost, energy, emissions and the cost
 * and emissions avoided versus a premium-by-default baseline.
 *
 * <p>{@code gramsCo2Avoided} and {@code costAvoided} are the headline figures
 * that quantify the value of caching + routing: how much CO2 and money were
 * saved by not sending every request to the most capable (and most carbon
 * intensive) model.
 *
 * <p>Since v4 B.6 a cost never travels without what it is in: the ISO 4217
 * {@code currency} and the {@link PricingBasis} of the price behind it. Until then
 * the figure was named {@code costEur} whatever the configured prices were in.
 *
 * @param cost            monetary cost of the request
 * @param energyKwh       estimated electrical energy consumed, in kWh
 * @param gramsCo2        estimated emissions of the actual call, in gCO2-eq
 * @param costAvoided     money saved vs a premium-default call
 * @param gramsCo2Avoided emissions saved vs a premium-default call, in gCO2-eq
 * @param pricingBasis    how {@code cost} was priced: the served model's price, or
 *                        on a cache hit the baseline's, which priced the avoided cost
 * @param currency        ISO 4217 code of both costs
 */
public record GreenMetrics(
    double cost,
    double energyKwh,
    double gramsCo2,
    double costAvoided,
    double gramsCo2Avoided,
    PricingBasis pricingBasis,
    String currency
) {

  public GreenMetrics {
    if (pricingBasis == null) {
      pricingBasis = PricingBasis.NONE;
    }
  }

  /** Nothing accounted, used when usage is unknown or the model is unmetered. */
  public static GreenMetrics none(String currency) {
    return new GreenMetrics(0.0, 0.0, 0.0, 0.0, 0.0, PricingBasis.NONE, currency);
  }
}
