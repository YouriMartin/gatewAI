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
   * Current grid carbon intensity, in gCO2-equivalent per kWh.
   *
   * @return a non-negative intensity; implementations should fall back to a
   *     sane default rather than throw if a live source is unavailable
   */
  double gramsCo2PerKwh();
}
