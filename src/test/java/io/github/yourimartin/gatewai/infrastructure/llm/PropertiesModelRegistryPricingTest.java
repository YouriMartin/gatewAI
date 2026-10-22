package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

import org.junit.jupiter.api.Test;

/** How {@code gatewai.models.registry.<key>} prices bind (v4 B.6). */
class PropertiesModelRegistryPricingTest {

  @Test
  void splitRatesBindPerMillion() {
    ModelRegistryProperties.ModelEntry entry = entry();
    entry.getPricing().setInputPer1m(5.0);
    entry.getPricing().setOutputPer1m(25.0);
    entry.getPricing().setCachedInputPer1m(0.5);

    assertThat(priceOf(entry)).isEqualTo(ModelPricing.split(5.0, 25.0, 0.5));
  }

  @Test
  void theCachedRateIsOptional() {
    ModelRegistryProperties.ModelEntry entry = entry();
    entry.getPricing().setInputPer1m(1.0);
    entry.getPricing().setOutputPer1m(5.0);

    assertThat(priceOf(entry)).isEqualTo(ModelPricing.split(1.0, 5.0, null));
  }

  @Test
  void theDeprecatedRateStillBindsAsBlended() {
    ModelRegistryProperties.ModelEntry entry = entry();
    entry.setCostPer1kTokens(0.015);

    assertThat(priceOf(entry)).isEqualTo(ModelPricing.blended(0.015));
  }

  @Test
  void anEntryWithNoPriceIsNotPriced() {
    assertThat(priceOf(entry())).isEqualTo(ModelPricing.NONE);
  }

  @Test
  void bothKindsOfPriceAtOnceFailStartup() {
    ModelRegistryProperties.ModelEntry entry = entry();
    entry.setCostPer1kTokens(0.015);
    entry.getPricing().setInputPer1m(5.0);
    entry.getPricing().setOutputPer1m(25.0);

    assertThatThrownBy(() -> priceOf(entry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gatewai.models.registry.m declares both pricing.*");
  }

  @Test
  void aSplitPriceWithoutItsOutputRateFailsStartupRatherThanReadingAsFree() {
    ModelRegistryProperties.ModelEntry entry = entry();
    entry.getPricing().setInputPer1m(5.0);

    assertThatThrownBy(() -> priceOf(entry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("needs both input-per-1m and output-per-1m");
  }

  @Test
  void aNegativeRateFailsStartupNamingTheEntry() {
    ModelRegistryProperties.ModelEntry entry = entry();
    entry.getPricing().setInputPer1m(-5.0);
    entry.getPricing().setOutputPer1m(25.0);

    assertThatThrownBy(() -> priceOf(entry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gatewai.models.registry.m:");
  }

  private static ModelRegistryProperties.ModelEntry entry() {
    ModelRegistryProperties.ModelEntry entry = new ModelRegistryProperties.ModelEntry();
    entry.setProvider("p");
    entry.setModelId("m");
    entry.setTier(ModelTier.LOCAL);
    return entry;
  }

  private static ModelPricing priceOf(ModelRegistryProperties.ModelEntry entry) {
    Map<String, ModelRegistryProperties.ModelEntry> registry = new LinkedHashMap<>();
    registry.put("m", entry);
    ModelRegistryProperties properties = new ModelRegistryProperties();
    properties.setRegistry(registry);
    return new PropertiesModelRegistry(properties).findByKey("m").orElseThrow().pricing();
  }
}
