package com.example.gatewai.infrastructure.carbon;

import com.example.gatewai.domain.port.out.CarbonIntensityProvider;

import org.springframework.stereotype.Component;

/**
 * Default {@link CarbonIntensityProvider}: returns the static regional value
 * from {@link CarbonProperties}. Always available (no external dependency) and
 * used as the fallback when the real-time provider is enabled but unreachable.
 *
 * <p>Read per call, so a configuration change applied at runtime takes effect
 * without rebuilding the bean.
 */
@Component
class StaticCarbonIntensityProvider implements CarbonIntensityProvider {

  private final CarbonProperties properties;

  StaticCarbonIntensityProvider(CarbonProperties properties) {
    this.properties = properties;
  }

  @Override
  public double gramsCo2PerKwh() {
    return properties.getGridIntensityGramsPerKwh();
  }

  @Override
  public double gramsCo2PerKwh(String zone) {
    return properties.getZoneIntensities()
        .getOrDefault(zone, properties.getGridIntensityGramsPerKwh());
  }
}
