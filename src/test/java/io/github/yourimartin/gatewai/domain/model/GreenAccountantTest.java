package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GreenAccountantTest {

  private static final double DELTA = 1e-9;
  private static final double GRID = 230.0;
  /** Every case here states its own PUE, so the arithmetic stays readable. */
  private static final double NO_OVERHEAD = 1.0;

  private GreenAccountant accountant;

  @BeforeEach
  void setUp() {
    accountant = new GreenAccountant(new CarbonCalculator());
  }

  /** Decode-only coefficients keep the arithmetic below one multiplication deep. */
  private static ModelDefinition premium() {
    return new ModelDefinition("sonnet", "anthropic", "claude-sonnet", 0.015,
        new EnergyProfile(0.0, 0.005, 0.0, EnergySource.MODELLED, false),
        ModelTier.CLOUD_PREMIUM);
  }

  private static ModelDefinition entry() {
    return new ModelDefinition("haiku", "anthropic", "claude-haiku", 0.002,
        new EnergyProfile(0.0, 0.002, 0.0, EnergySource.MODELLED, false),
        ModelTier.CLOUD_ENTRY);
  }

  /** A site on the shipped grid with no datacenter overhead. */
  private static ModelSite site(ModelDefinition model) {
    return new ModelSite(model, GRID, NO_OVERHEAD);
  }

  private static ModelSite site(ModelDefinition model, double intensity) {
    return new ModelSite(model, intensity, NO_OVERHEAD);
  }

  /** All tokens on the decode side, so `n` tokens cost `n × decode` energy. */
  private static TokenUsage decoding(int tokens) {
    return TokenUsage.of(0, tokens);
  }

  @Test
  void computesCostEnergyAndCarbon() {
    // 2000 completion tokens on entry: cost = 2 * 0.002 = 0.004 ;
    // energy = 2 * 0.002 = 0.004 kWh ; carbon = 0.004 * 230 = 0.92 gCO2
    GreenMetrics metrics = accountant.account(
        site(entry()), site(premium()), decoding(2000), false);

    assertEquals(0.004, metrics.costEur(), DELTA);
    assertEquals(0.004, metrics.energyKwh(), DELTA);
    assertEquals(0.92, metrics.gramsCo2(), DELTA);
  }

  @Test
  void promptTokensAreChargedAtThePrefillRateAndBilledInTheTotal() {
    ModelDefinition split = new ModelDefinition("split", "anthropic", "split", 0.01,
        new EnergyProfile(0.0005, 0.01, 0.0, EnergySource.MODELLED, false),
        ModelTier.CLOUD_PREMIUM);

    GreenMetrics metrics = accountant.account(
        site(split), null, TokenUsage.of(4000, 200), false);

    // energy = 4 x 0.0005 + 0.2 x 0.01 = 0.004 kWh; cost bills all 4200 tokens.
    assertEquals(0.004, metrics.energyKwh(), DELTA);
    assertEquals(4.2 * 0.01, metrics.costEur(), DELTA);
  }

  @Test
  void avoidedIsPremiumMinusActualEmission() {
    // 1000 tokens: premium carbon = 0.005*230 = 1.15 ;
    // entry carbon = 0.002*230 = 0.46 ; avoided = 0.69
    GreenMetrics metrics = accountant.account(
        site(entry()), site(premium()), decoding(1000), false);

    assertEquals(0.69, metrics.gramsCo2Avoided(), DELTA);
    // premium cost 0.015 - entry cost 0.002 = 0.013 EUR avoided
    assertEquals(0.013, metrics.costAvoidedEur(), DELTA);
  }

  @Test
  void noAvoidanceWhenServedByPremiumItself() {
    GreenMetrics metrics = accountant.account(
        site(premium()), site(premium()), decoding(1000), false);

    assertEquals(0.0, metrics.gramsCo2Avoided(), DELTA);
  }

  @Test
  void avoidanceNeverNegativeWhenActualDirtierThanBaseline() {
    ModelDefinition dirty = new ModelDefinition("dirty", "x", "dirty", 0.0,
        new EnergyProfile(0.0, 0.01, 0.0, EnergySource.MODELLED, false),
        ModelTier.LOCAL);

    GreenMetrics metrics = accountant.account(
        site(dirty), site(premium()), decoding(1000), false);

    assertTrue(metrics.gramsCo2Avoided() >= 0.0);
    assertEquals(0.0, metrics.gramsCo2Avoided(), DELTA);
  }

  @Test
  void noBaselineMeansNoAvoidance() {
    GreenMetrics metrics =
        accountant.account(site(entry()), null, decoding(1000), false);

    assertEquals(0.0, metrics.gramsCo2Avoided(), DELTA);
    assertTrue(metrics.gramsCo2() > 0.0);
  }

  @Test
  void unknownUsedModelReturnsZeroOnMiss() {
    assertSame(GreenMetrics.ZERO,
        accountant.account(null, site(premium()), decoding(1000), false));
    assertSame(GreenMetrics.ZERO,
        accountant.account(site(null), site(premium()), decoding(1000), false));
  }

  @Test
  void zeroTokensReturnsZero() {
    assertSame(GreenMetrics.ZERO,
        accountant.account(site(entry()), site(premium()), TokenUsage.NONE, false));
    assertSame(GreenMetrics.ZERO,
        accountant.account(site(entry()), site(premium()), null, false));
  }

  @Test
  void anUnaccountedModelCostsMoneyButBooksNoEmissions() {
    // Local egress is out of scope (lot C.1) — zero, and labelled, never measured.
    ModelDefinition local = new ModelDefinition("local", "ollama", "qwen2.5:3b", 0.0,
        EnergyProfile.NOT_ACCOUNTED, ModelTier.LOCAL);

    GreenMetrics metrics = accountant.account(
        site(local), site(premium()), decoding(1000), false);

    assertEquals(0.0, metrics.energyKwh(), DELTA);
    assertEquals(0.0, metrics.gramsCo2(), DELTA);
    // The baseline is still accounted, so the avoided figure is the whole of it.
    assertEquals(1.15, metrics.gramsCo2Avoided(), DELTA);
  }

  // ---- Cache hit: no inference, full premium call credited as avoided ----

  @Test
  void cacheHitHasZeroRealCostAndCarbon() {
    GreenMetrics metrics = accountant.account(
        site(entry()), site(premium()), decoding(1000), true);

    assertEquals(0.0, metrics.costEur(), DELTA);
    assertEquals(0.0, metrics.energyKwh(), DELTA);
    assertEquals(0.0, metrics.gramsCo2(), DELTA);
  }

  @Test
  void cacheHitCreditsFullPremiumEmissionAsAvoided() {
    GreenMetrics metrics = accountant.account(
        site(entry()), site(premium()), decoding(1000), true);

    assertEquals(1.15, metrics.gramsCo2Avoided(), DELTA);
    assertEquals(0.015, metrics.costAvoidedEur(), DELTA);
  }

  @Test
  void cacheHitWithoutUsedModelStillCreditsAvoided() {
    GreenMetrics metrics =
        accountant.account(null, site(premium()), decoding(1000), true);

    assertEquals(1.15, metrics.gramsCo2Avoided(), DELTA);
  }

  @Test
  void cacheHitWithoutBaselineCreditsNothing() {
    GreenMetrics metrics =
        accountant.account(site(entry()), null, decoding(1000), true);

    assertEquals(0.0, metrics.gramsCo2Avoided(), DELTA);
  }

  @Test
  void theBaselineIsPricedAtItsOwnGridNotAtTheServedModelsGrid() {
    // The premium baseline is a counterfactual about ANOTHER datacenter: 2000
    // tokens on an entry model at France's 56, against the premium model as it
    // would have run on a US grid at 350.
    GreenMetrics metrics = accountant.account(
        site(entry(), 56.0), site(premium(), 350.0), decoding(2000), false);

    // actual  = 2 x 0.002 kWh x 56  = 0.224 gCO2
    // baseline= 2 x 0.005 kWh x 350 = 3.5   gCO2
    assertEquals(0.224, metrics.gramsCo2(), DELTA);
    assertEquals(3.5 - 0.224, metrics.gramsCo2Avoided(), DELTA);
  }

  @Test
  void aCacheHitCreditsTheBaselineAtTheBaselinesGrid() {
    GreenMetrics metrics = accountant.account(
        site(entry(), 56.0), site(premium(), 350.0), decoding(2000), true);

    assertEquals(0.0, metrics.gramsCo2(), DELTA);
    // 2 x 0.005 kWh x 350: the call that did not happen would have happened there.
    assertEquals(3.5, metrics.gramsCo2Avoided(), DELTA);
  }

  @Test
  void eachSideCarriesItsOwnDatacenterEfficiency() {
    // Served on a PUE 1.5 box, baseline on a PUE 1.1 one.
    GreenMetrics metrics = accountant.account(
        new ModelSite(entry(), GRID, 1.5), new ModelSite(premium(), GRID, 1.1),
        decoding(1000), false);

    assertEquals(0.002 * 1.5, metrics.energyKwh(), DELTA);
    assertEquals(0.002 * 1.5 * GRID, metrics.gramsCo2(), DELTA);
    assertEquals(0.005 * 1.1 * GRID - 0.002 * 1.5 * GRID,
        metrics.gramsCo2Avoided(), DELTA);
  }
}
