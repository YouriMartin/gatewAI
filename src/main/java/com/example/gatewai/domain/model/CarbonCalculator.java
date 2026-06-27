package com.example.gatewai.domain.model;

/**
 * Carbon model (Phase 4.1): converts token usage into an estimated energy and
 * carbon footprint.
 *
 * <p>The chain is {@code tokens → kWh} (per-model energy intensity)
 * {@code → gCO2} (grid carbon intensity). Pure domain logic, zero framework
 * dependencies. The grid carbon intensity is supplied by the caller, so it can
 * be a static regional constant now and a live value from a
 * {@code CarbonIntensityProvider} later (Phase 4.2) without changing this class.
 */
public final class CarbonCalculator {

  private static final double TOKENS_PER_UNIT = 1000.0;

  /**
   * Estimates the footprint of an inference.
   *
   * @param model                    the model that served the request
   * @param tokens                   total tokens billed (prompt + completion)
   * @param gridIntensityGramsPerKwh electricity grid carbon intensity, gCO2/kWh
   * @return the estimated energy and carbon footprint, never {@code null}
   */
  public CarbonFootprint estimate(ModelDefinition model, long tokens,
                                  double gridIntensityGramsPerKwh) {
    if (model == null || tokens <= 0) {
      return CarbonFootprint.ZERO;
    }
    double energyKwh = (tokens / TOKENS_PER_UNIT) * model.energyIntensity();
    double gramsCo2 = energyKwh * gridIntensityGramsPerKwh;
    return new CarbonFootprint(energyKwh, gramsCo2);
  }
}
