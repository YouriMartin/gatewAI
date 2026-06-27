package com.example.gatewai.infrastructure.carbon;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Static carbon-model configuration (Phase 4.1).
 *
 * <p>The grid carbon intensity is a static regional placeholder here. Phase 4.2
 * replaces it with a swappable {@code CarbonIntensityProvider} backed by a
 * real-time API (ElectricityMaps / WattTime).
 */
@ConfigurationProperties(prefix = "gatewai.carbon")
class CarbonProperties {

  /** Electricity grid carbon intensity, in gCO2-equivalent per kWh. */
  private double gridIntensityGramsPerKwh = 230.0;

  double getGridIntensityGramsPerKwh() {
    return gridIntensityGramsPerKwh;
  }

  void setGridIntensityGramsPerKwh(double gridIntensityGramsPerKwh) {
    this.gridIntensityGramsPerKwh = gridIntensityGramsPerKwh;
  }
}
