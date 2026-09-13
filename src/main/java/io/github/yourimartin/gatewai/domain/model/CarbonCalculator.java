package io.github.yourimartin.gatewai.domain.model;

/**
 * Carbon model: turns token usage into an estimated energy and carbon footprint.
 *
 * <p>The chain is {@code tokens → kWh} (the model's {@link EnergyProfile},
 * × datacenter PUE) {@code → gCO2} (grid carbon intensity). Pure domain logic, zero
 * framework dependencies. Both the intensity and the PUE are supplied by the caller
 * through a {@link ModelSite}, so the same class prices a request at the grid that
 * actually served it (v3 lot C.3) without knowing where that came from.
 *
 * <p>Since v3 lot C.4 the energy side is a prefill/decode split rather than one
 * scalar per 1k tokens, so a long prompt with a short answer and its mirror image no
 * longer produce the same number. Nothing here is measured: every coefficient is
 * sourced and labelled, and {@code docs/technical/green-accounting.md} carries the
 * table with each source and the date it was read.
 */
public final class CarbonCalculator {

  /**
   * PUE applied when a provider instance declares none. EcoLogits' methodology uses
   * a default of <b>1.2</b> and per-provider values in the 1.09–1.20 range; taking
   * the top of that range means an undeclared datacenter is never flattered.
   * Read 2026-09-13: https://ecologits.ai/latest/methodology/llm_inference/
   */
  public static final double DEFAULT_PUE = 1.2;

  /**
   * Estimates the footprint of one inference.
   *
   * @param site  the model and the conditions it ran under; a {@code null} site or
   *              model yields {@link CarbonFootprint#ZERO}
   * @param usage token counts as the provider reported them
   * @return the estimated energy and carbon footprint, never {@code null}
   */
  public CarbonFootprint estimate(ModelSite site, TokenUsage usage) {
    if (site == null || site.model() == null || usage == null) {
      return CarbonFootprint.ZERO;
    }
    EnergyProfile energy = site.model().energy();
    if (!energy.accounted()) {
      // Excluded from scope (lot C.1): booked at zero, and rendered as excluded.
      return CarbonFootprint.ZERO;
    }
    double itEnergyKwh = energy.kwh(usage.promptTokens(), usage.completionTokens());
    if (itEnergyKwh <= 0.0) {
      return CarbonFootprint.ZERO;
    }
    double energyKwh = itEnergyKwh * overheadFactor(site, energy);
    return new CarbonFootprint(energyKwh, energyKwh * site.gridIntensityGramsPerKwh());
  }

  /**
   * PUE, unless the coefficients already carry facility overhead — a full-stack
   * vendor figure multiplied by PUE would count cooling twice.
   */
  private static double overheadFactor(ModelSite site, EnergyProfile energy) {
    if (energy.includesDatacenterOverhead()) {
      return 1.0;
    }
    Double pue = site.pue();
    return pue == null ? DEFAULT_PUE : pue;
  }
}
