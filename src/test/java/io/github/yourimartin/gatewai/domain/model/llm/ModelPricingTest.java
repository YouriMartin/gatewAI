package io.github.yourimartin.gatewai.domain.model.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Split pricing (v4 B.6), the roadmap's acceptance cases first. */
class ModelPricingTest {

  /** Opus-class list rates, per million: input 5, output 25, cached input 0.50. */
  private static final ModelPricing SPLIT = ModelPricing.split(5.0, 25.0, 0.50);
  private static final TokenUsage INPUT_HEAVY = TokenUsage.of(10_000, 50);
  private static final TokenUsage OUTPUT_HEAVY = TokenUsage.of(50, 10_000);

  @Test
  void mirrorImageRequestsDifferByThePriceRatio() {
    double inputHeavy = SPLIT.cost(INPUT_HEAVY);
    double outputHeavy = SPLIT.cost(OUTPUT_HEAVY);

    assertThat(inputHeavy).isCloseTo((10_000 * 5.0 + 50 * 25.0) / 1e6, within(1e-12));
    assertThat(outputHeavy).isCloseTo((50 * 5.0 + 10_000 * 25.0) / 1e6, within(1e-12));
    // Dominated by the dominant phase, so the ratio tends to output/input = 5.
    assertThat(outputHeavy / inputHeavy).isCloseTo(250_250.0 / 51_250.0, within(1e-9));
  }

  @Test
  void aBlendedPriceCannotTellTheMirrorImagesApart() {
    ModelPricing blended = ModelPricing.blended(0.015);

    assertThat(blended.cost(INPUT_HEAVY)).isEqualTo(blended.cost(OUTPUT_HEAVY));
    assertThat(blended.cost(INPUT_HEAVY)).isCloseTo(10_050 / 1000.0 * 0.015, within(1e-12));
  }

  @Test
  void cachedPromptTokensArePricedAtTheCachedRateWhenReported() {
    TokenUsage cached = new TokenUsage(10_000, 50, 10_050, 8_000);

    assertThat(SPLIT.cost(cached))
        .isCloseTo((2_000 * 5.0 + 8_000 * 0.50 + 50 * 25.0) / 1e6, within(1e-12));
  }

  @Test
  void unreportedCachedTokensArePricedAtTheInputRate() {
    TokenUsage unreported = new TokenUsage(10_000, 50, 10_050, null);

    assertThat(SPLIT.cost(unreported)).isEqualTo(SPLIT.cost(INPUT_HEAVY));
  }

  @Test
  void aModelWithNoCachedRatePricesCachedTokensAtTheInputRate() {
    ModelPricing noCachedRate = ModelPricing.split(5.0, 25.0, null);

    assertThat(noCachedRate.cost(new TokenUsage(10_000, 50, 10_050, 8_000)))
        .isEqualTo(noCachedRate.cost(INPUT_HEAVY));
  }

  @Test
  void aCachedCountAboveThePromptIsCappedAtThePrompt() {
    assertThat(SPLIT.cost(new TokenUsage(100, 0, 100, 500)))
        .isCloseTo(100 * 0.50 / 1e6, within(1e-15));
  }

  @Test
  void noPriceCostsNothingAndSaysSo() {
    assertThat(ModelPricing.NONE.cost(INPUT_HEAVY)).isZero();
    assertThat(ModelPricing.NONE.basis()).isEqualTo(PricingBasis.NONE);
  }

  @Test
  void aNegativeRateIsRefused() {
    assertThatThrownBy(() -> ModelPricing.split(-1.0, 25.0, null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input");
    assertThatThrownBy(() -> ModelPricing.split(5.0, 25.0, -0.1))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cached input");
    assertThatThrownBy(() -> ModelPricing.blended(Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
