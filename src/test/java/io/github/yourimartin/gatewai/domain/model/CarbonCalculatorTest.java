package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * tokens → kWh (× PUE) → gCO2, with the phase split of v3 lot C.4 and the
 * per-request grid of C.3.
 */
class CarbonCalculatorTest {

  private static final double DELTA = 1e-12;
  private static final double GRID = 230.0;

  private final CarbonCalculator calculator = new CarbonCalculator();

  private static ModelDefinition model(EnergyProfile energy) {
    return new ModelDefinition("key", "anthropic", "model-id", 0.015, energy,
        ModelTier.CLOUD_PREMIUM);
  }

  private static ModelDefinition modelled(double prefill, double decode) {
    return model(new EnergyProfile(prefill, decode, 0.0, EnergySource.MODELLED, false));
  }

  @Test
  void chargesPrefillAndDecodeSeparatelyThenAppliesPueAndGrid() {
    // 2000 prompt x 0.001 + 500 completion x 0.01 = 0.007 kWh IT energy,
    // x PUE 1.1 = 0.0077 kWh, x 230 gCO2/kWh = 1.771 gCO2.
    ModelSite site = new ModelSite(modelled(0.001, 0.01), GRID, 1.1);

    CarbonFootprint footprint =
        calculator.estimate(site, TokenUsage.of(2000, 500));

    assertEquals(0.0077, footprint.energyKwh(), DELTA);
    assertEquals(1.771, footprint.gramsCo2(), 1e-9);
  }

  @Test
  void aLongPromptAndItsMirrorImageNoLongerAgree() {
    ModelSite site = new ModelSite(modelled(0.000393, 0.0161), GRID, 1.0);

    CarbonFootprint longPrompt =
        calculator.estimate(site, TokenUsage.of(10_000, 50));
    CarbonFootprint longAnswer =
        calculator.estimate(site, TokenUsage.of(50, 10_000));

    // Same 10 050 tokens, ~34x the energy when they are generated instead of read.
    assertEquals(longPrompt.energyKwh() * 34.0, longAnswer.energyKwh(), 1e-3);
    assertTrue(longAnswer.gramsCo2() > longPrompt.gramsCo2());
  }

  @Test
  void anUndeclaredPueFallsBackToTheDocumentedDefault() {
    ModelSite site = ModelSite.at(modelled(0.0, 0.01), GRID);

    CarbonFootprint footprint = calculator.estimate(site, TokenUsage.of(0, 1000));

    assertEquals(0.01 * CarbonCalculator.DEFAULT_PUE, footprint.energyKwh(), DELTA);
    assertEquals(1.2, CarbonCalculator.DEFAULT_PUE);
  }

  @Test
  void aFullStackVendorFigureIsNotMultipliedByPueAgain() {
    // 0.24 Wh per prompt, published including datacenter overhead: applying PUE
    // would count cooling twice.
    EnergyProfile published = new EnergyProfile(
        0.0, 0.0, 0.00024, EnergySource.VENDOR_PUBLISHED, true);
    ModelSite site = new ModelSite(model(published), GRID, 1.5);

    CarbonFootprint footprint = calculator.estimate(site, TokenUsage.of(500, 300));

    assertEquals(0.00024, footprint.energyKwh(), DELTA);
  }

  @Test
  void aFixedPerRequestFigureIgnoresTheTokenMix() {
    EnergyProfile published = new EnergyProfile(
        0.0, 0.0, 0.00024, EnergySource.VENDOR_PUBLISHED, true);
    ModelSite site = new ModelSite(model(published), GRID, null);

    assertEquals(calculator.estimate(site, TokenUsage.of(10, 10)).energyKwh(),
        calculator.estimate(site, TokenUsage.of(9000, 9000)).energyKwh(), DELTA);
  }

  @Test
  void anUnaccountedModelIsZeroAndStaysZero() {
    ModelSite site = new ModelSite(model(EnergyProfile.NOT_ACCOUNTED), GRID, 1.2);

    assertSame(CarbonFootprint.ZERO, calculator.estimate(site, TokenUsage.of(5000, 5000)));
  }

  @Test
  void noUsageOrNoModelMeansNoFootprint() {
    ModelSite site = ModelSite.at(modelled(0.001, 0.01), GRID);

    assertSame(CarbonFootprint.ZERO, calculator.estimate(site, TokenUsage.NONE));
    assertSame(CarbonFootprint.ZERO, calculator.estimate(site, null));
    assertSame(CarbonFootprint.ZERO, calculator.estimate(null, TokenUsage.of(10, 10)));
    assertSame(CarbonFootprint.ZERO,
        calculator.estimate(new ModelSite(null, GRID, null), TokenUsage.of(10, 10)));
  }

  @Test
  void aZeroIntensityGridMeansEnergyWithoutEmissions() {
    ModelSite site = new ModelSite(modelled(0.0, 0.01), 0.0, 1.0);

    CarbonFootprint footprint = calculator.estimate(site, TokenUsage.of(0, 1000));

    assertEquals(0.01, footprint.energyKwh(), DELTA);
    assertEquals(0.0, footprint.gramsCo2(), DELTA);
  }
}
