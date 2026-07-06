package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarbonCalculatorTest {

  private static final double DELTA = 1e-9;
  private static final double GRID_INTENSITY = 230.0;

  private CarbonCalculator calculator;

  @BeforeEach
  void setUp() {
    calculator = new CarbonCalculator();
  }

  private static ModelDefinition model(double energyIntensity) {
    return new ModelDefinition(
        "key", "anthropic", "model-id",
        0.015, energyIntensity, ModelTier.CLOUD_PREMIUM);
  }

  @Test
  void computesEnergyAndCarbonFromTokens() {
    // 2000 tokens * 0.005 kWh/1k = 0.01 kWh ; * 230 gCO2/kWh = 2.3 gCO2
    CarbonFootprint footprint =
        calculator.estimate(model(0.005), 2000, GRID_INTENSITY);

    assertEquals(0.01, footprint.energyKwh(), DELTA);
    assertEquals(2.3, footprint.gramsCo2(), DELTA);
  }

  @Test
  void energyScalesLinearlyWithTokens() {
    ModelDefinition m = model(0.005);

    CarbonFootprint one = calculator.estimate(m, 1000, GRID_INTENSITY);
    CarbonFootprint ten = calculator.estimate(m, 10_000, GRID_INTENSITY);

    assertEquals(one.energyKwh() * 10, ten.energyKwh(), DELTA);
    assertEquals(one.gramsCo2() * 10, ten.gramsCo2(), DELTA);
  }

  @Test
  void carbonScalesWithGridIntensity() {
    ModelDefinition m = model(0.005);

    CarbonFootprint clean = calculator.estimate(m, 1000, 50.0);
    CarbonFootprint dirty = calculator.estimate(m, 1000, 500.0);

    assertEquals(clean.energyKwh(), dirty.energyKwh(), DELTA);
    assertTrue(dirty.gramsCo2() > clean.gramsCo2());
  }

  @Test
  void greenerModelEmitsLessForSameTokens() {
    CarbonFootprint premium =
        calculator.estimate(model(0.005), 1000, GRID_INTENSITY);
    CarbonFootprint local =
        calculator.estimate(model(0.001), 1000, GRID_INTENSITY);

    assertTrue(local.gramsCo2() < premium.gramsCo2());
  }

  @Test
  void zeroTokensReturnsZeroFootprint() {
    assertSame(CarbonFootprint.ZERO,
        calculator.estimate(model(0.005), 0, GRID_INTENSITY));
  }

  @Test
  void negativeTokensReturnsZeroFootprint() {
    assertSame(CarbonFootprint.ZERO,
        calculator.estimate(model(0.005), -100, GRID_INTENSITY));
  }

  @Test
  void nullModelReturnsZeroFootprint() {
    assertSame(CarbonFootprint.ZERO,
        calculator.estimate(null, 1000, GRID_INTENSITY));
  }
}
