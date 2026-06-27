package com.example.gatewai.domain.port.out;

/**
 * Supplies the current electricity-grid carbon intensity used to turn estimated
 * energy (kWh) into emissions (gCO2). Outbound port: implementations may read a
 * static configured value or call a real-time API (ElectricityMaps, WattTime).
 *
 * <p>The abstraction keeps the carbon model decoupled from any specific data
 * source and trivially mockable in tests.
 */
public interface CarbonIntensityProvider {

  /**
   * Current grid carbon intensity for the default zone, in gCO2-eq per kWh.
   *
   * @return a non-negative intensity; implementations should fall back to a
   *     sane default rather than throw if a live source is unavailable
   */
  double gramsCo2PerKwh();

  /**
   * Current grid carbon intensity for a specific zone, in gCO2-eq per kWh.
   * Used by carbon-aware geographic routing (Phase 4.4).
   *
   * @param zone grid zone id (e.g. {@code FR}, {@code SE})
   * @return a non-negative intensity, falling back to the default when the
   *     zone is unknown or a live source is unavailable
   */
  double gramsCo2PerKwh(String zone);
}
