package io.github.yourimartin.gatewai.domain.model.carbon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.llm.PricingBasis;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

import org.junit.jupiter.api.Test;

/** What the accountant does with split prices and the billing currency (v4 B.6). */
class GreenAccountantPricingTest {

  private static final ModelDefinition PREMIUM = model("premium",
      ModelPricing.split(5.0, 25.0, 0.50), ModelTier.CLOUD_PREMIUM);
  private static final ModelDefinition ENTRY = model("entry",
      ModelPricing.split(1.0, 5.0, null), ModelTier.CLOUD_ENTRY);
  private static final ModelDefinition LOCAL = model("local", ModelPricing.NONE, ModelTier.LOCAL);
  private static final TokenUsage INPUT_HEAVY = TokenUsage.of(10_000, 50);

  private final GreenAccountant accountant = new GreenAccountant(new CarbonCalculator(), "usd");

  @Test
  void theServedModelsSplitPriceAndItsBasisAreStamped() {
    GreenMetrics metrics = accountant.account(site(ENTRY), site(PREMIUM), INPUT_HEAVY, false);

    assertThat(metrics.cost()).isCloseTo((10_000 * 1.0 + 50 * 5.0) / 1e6, within(1e-12));
    assertThat(metrics.pricingBasis()).isEqualTo(PricingBasis.SPLIT);
    assertThat(metrics.currency()).isEqualTo("USD");
  }

  @Test
  void theAvoidedCostComparesSplitPrices() {
    GreenMetrics metrics = accountant.account(site(ENTRY), site(PREMIUM), INPUT_HEAVY, false);

    double premium = (10_000 * 5.0 + 50 * 25.0) / 1e6;
    double entry = (10_000 * 1.0 + 50 * 5.0) / 1e6;
    assertThat(metrics.costAvoided()).isCloseTo(premium - entry, within(1e-12));
  }

  @Test
  void anUnpricedModelCostsNothingButStillAvoidsThePremiumCost() {
    GreenMetrics metrics = accountant.account(site(LOCAL), site(PREMIUM), INPUT_HEAVY, false);

    assertThat(metrics.cost()).isZero();
    assertThat(metrics.pricingBasis()).isEqualTo(PricingBasis.NONE);
    assertThat(metrics.costAvoided()).isCloseTo(PREMIUM.pricing().cost(INPUT_HEAVY),
        within(1e-12));
  }

  @Test
  void aCacheHitIsLabelledWithTheBaselinesBasis() {
    GreenMetrics metrics = accountant.account(null, site(PREMIUM), INPUT_HEAVY, true);

    assertThat(metrics.cost()).isZero();
    assertThat(metrics.costAvoided()).isCloseTo(PREMIUM.pricing().cost(INPUT_HEAVY),
        within(1e-12));
    assertThat(metrics.pricingBasis()).isEqualTo(PricingBasis.SPLIT);
  }

  @Test
  void anUnknownCurrencyFailsAtConstruction() {
    assertThatThrownBy(() -> new GreenAccountant(new CarbonCalculator(), "EURO"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gatewai.billing.currency='EURO'");
  }

  private static ModelSite site(ModelDefinition model) {
    return new ModelSite(model, 230.0, 1.0);
  }

  private static ModelDefinition model(String id, ModelPricing pricing, ModelTier tier) {
    return new ModelDefinition(id, "provider", id, pricing, EnergyProfile.NOT_ACCOUNTED, tier);
  }
}
