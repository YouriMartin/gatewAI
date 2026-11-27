package io.github.yourimartin.gatewai.domain.model.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.carbon.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.llm.PricingBasis;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;

import org.junit.jupiter.api.Test;

class ShadowCounterfactualTest {

  private static final TokenUsage USAGE = TokenUsage.of(100, 20);

  @Test
  void aRoutedCounterfactualKeepsItsPriceAndAvoidsNothing() {
    ShadowCounterfactual routed = counterfactual(CounterfactualOutcome.ROUTED, "qwen2.5:3b",
        new GreenMetrics(0.002, 0.0001, 0.04, 0.5, 9.0, PricingBasis.SPLIT, "EUR"));

    assertThat(routed.metrics().cost()).isEqualTo(0.002);
    assertThat(routed.metrics().gramsCo2()).isEqualTo(0.04);
    assertThat(routed.metrics().costAvoided())
        .as("avoided figures compare a served request with the premium baseline")
        .isZero();
    assertThat(routed.metrics().gramsCo2Avoided()).isZero();
  }

  @Test
  void aWouldHitNamesNoModelAndCostsNothing() {
    ShadowCounterfactual hit = counterfactual(CounterfactualOutcome.WOULD_HIT, null,
        GreenMetrics.none("EUR"));
    assertThat(hit.model()).isNull();

    assertThatThrownBy(() -> counterfactual(CounterfactualOutcome.WOULD_HIT, "qwen2.5:3b",
        GreenMetrics.none("EUR"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> counterfactual(CounterfactualOutcome.WOULD_HIT, null,
        new GreenMetrics(0.001, 0, 0, 0, 0, PricingBasis.SPLIT, "EUR")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anythingButAWouldHitNamesItsModel() {
    assertThatThrownBy(() -> counterfactual(CounterfactualOutcome.ROUTED, null,
        GreenMetrics.none("EUR"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> counterfactual(CounterfactualOutcome.PASSED_THROUGH, null,
        GreenMetrics.none("EUR"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCounterfactualStatesItsCurrency() {
    assertThatThrownBy(() -> counterfactual(CounterfactualOutcome.ROUTED, "m",
        GreenMetrics.none(null))).isInstanceOf(NullPointerException.class);
  }

  private static ShadowCounterfactual counterfactual(CounterfactualOutcome outcome, String model,
                                                     GreenMetrics metrics) {
    return new ShadowCounterfactual(UUID.randomUUID(), "corr-1", "client-a",
        Instant.parse("2026-11-27T10:00:00Z"), outcome, model, USAGE, metrics,
        GreenProvenance.UNKNOWN);
  }
}
