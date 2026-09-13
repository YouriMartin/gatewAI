package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.ProviderRegion;
import io.github.yourimartin.gatewai.domain.model.RegionProvenance;

import org.junit.jupiter.api.Test;

/** Reads {@code gatewai.providers.<name>.region*} into the domain (v3 lot C.2). */
class PropertiesProviderRegionsTest {

  @Test
  void exposesDeclaredRegionsCaseInsensitively() {
    PropertiesProviderRegions regions = regions(Map.of(
        "Anthropic", entry("US-MIDA-PJM", RegionProvenance.ASSUMED, null),
        "vllm", entry("eu-west-3", RegionProvenance.KNOWN, 1.15)));

    assertThat(regions.findByProvider("ANTHROPIC")).isPresent();
    ProviderRegion vllm = regions.findByProvider("vllm").orElseThrow();
    assertThat(vllm.region()).isEqualTo("eu-west-3");
    assertThat(vllm.provenance()).isEqualTo(RegionProvenance.KNOWN);
    assertThat(vllm.pue()).isEqualTo(1.15);
    assertThat(regions.all()).hasSize(2);
  }

  @Test
  void anInstanceWithoutARegionIsAbsentRatherThanDefaulted() {
    PropertiesProviderRegions regions = regions(Map.of(
        "ollama", entry(null, null, null),
        "openai", entry("   ", null, null)));

    // Empty lets the caller fall back to the gateway zone (C.3) instead of
    // inventing a location for a provider that declared none.
    assertThat(regions.findByProvider("ollama")).isEmpty();
    assertThat(regions.findByProvider("openai")).isEmpty();
    assertThat(regions.findByProvider("nope")).isEmpty();
    assertThat(regions.findByProvider(null)).isEmpty();
    assertThat(regions.all()).isEmpty();
  }

  @Test
  void provenanceDefaultsToAssumedWhenOmitted() {
    PropertiesProviderRegions regions =
        regions(Map.of("anthropic", entry("US-MIDA-PJM", null, null)));

    assertThat(regions.findByProvider("anthropic").orElseThrow().provenance())
        .isEqualTo(RegionProvenance.ASSUMED);
  }

  @Test
  void anImpossiblePueFailsAtStartupNamingTheProperty() {
    assertThatThrownBy(() -> regions(Map.of("vllm", entry("eu-west-3", null, 0.5))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gatewai.providers.vllm.pue");
  }

  private static PropertiesProviderRegions regions(
      Map<String, ProviderProperties.ProviderEntry> entries) {
    ProviderProperties properties = new ProviderProperties();
    properties.setProviders(new LinkedHashMap<>(entries));
    return new PropertiesProviderRegions(properties);
  }

  private static ProviderProperties.ProviderEntry entry(String region,
                                                        RegionProvenance provenance,
                                                        Double pue) {
    ProviderProperties.ProviderEntry entry = new ProviderProperties.ProviderEntry();
    entry.setType(ProviderProperties.ProviderType.OPENAI);
    entry.setRegion(region);
    entry.setRegionProvenance(provenance);
    entry.setPue(pue);
    return entry;
  }
}
