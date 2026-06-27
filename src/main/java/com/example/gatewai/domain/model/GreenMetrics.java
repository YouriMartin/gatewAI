package com.example.gatewai.domain.model;

/**
 * Per-request green accounting: monetary cost, energy, emissions and the
 * emissions avoided versus a premium-by-default baseline.
 *
 * <p>{@code gramsCo2Avoided} is the headline figure that quantifies the value
 * of caching + routing: how much CO2 was saved by not sending every request to
 * the most capable (and most carbon-intensive) model.
 *
 * @param costEur         monetary cost of the request, in the billing currency
 * @param energyKwh       estimated electrical energy consumed, in kWh
 * @param gramsCo2        estimated emissions of the actual call, in gCO2-eq
 * @param gramsCo2Avoided emissions saved vs a premium-default call, in gCO2-eq
 */
public record GreenMetrics(
    double costEur,
    double energyKwh,
    double gramsCo2,
    double gramsCo2Avoided
) {

  /** Neutral metrics, used when usage is unknown or the model is unmetered. */
  public static final GreenMetrics ZERO = new GreenMetrics(0.0, 0.0, 0.0, 0.0);
}
