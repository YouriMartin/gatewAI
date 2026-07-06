package io.github.yourimartin.gatewai.domain.model;

/**
 * Estimated environmental footprint of a single inference.
 *
 * <p>Pure value object with zero framework dependencies. Energy is derived from
 * the model's per-token energy intensity; carbon from the electricity grid's
 * carbon intensity.
 *
 * @param energyKwh estimated electrical energy consumed, in kWh
 * @param gramsCo2  estimated CO2-equivalent emissions, in grams
 */
public record CarbonFootprint(double energyKwh, double gramsCo2) {

  /** A zero footprint, used when usage is unknown or empty. */
  public static final CarbonFootprint ZERO = new CarbonFootprint(0.0, 0.0);
}
