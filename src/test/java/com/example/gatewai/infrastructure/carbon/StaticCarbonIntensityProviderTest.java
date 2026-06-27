package com.example.gatewai.infrastructure.carbon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StaticCarbonIntensityProviderTest {

  @Test
  void returnsConfiguredValue() {
    CarbonProperties properties = new CarbonProperties();
    properties.setGridIntensityGramsPerKwh(56.0);

    StaticCarbonIntensityProvider provider =
        new StaticCarbonIntensityProvider(properties);

    assertEquals(56.0, provider.gramsCo2PerKwh());
  }

  @Test
  void reflectsRuntimeConfigChange() {
    CarbonProperties properties = new CarbonProperties();
    StaticCarbonIntensityProvider provider =
        new StaticCarbonIntensityProvider(properties);

    properties.setGridIntensityGramsPerKwh(480.0);

    assertEquals(480.0, provider.gramsCo2PerKwh());
  }
}
