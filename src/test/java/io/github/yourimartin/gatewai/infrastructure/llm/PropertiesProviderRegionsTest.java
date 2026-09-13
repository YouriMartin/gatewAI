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
    assertThat(regions.all()).allMatch(ProviderRegion::isDeclared);
  }

  @Test
  void anInstanceWithoutARegionIsPresentButDeclaresNone() {
    PropertiesProviderRegions regions = regions(Map.of(
        "ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null, null),
        "openai", entry(ProviderProperties.ProviderType.OPENAI, "   ", null, null)));

    // The instance is known — that is what carries operatorControlled (C.3) — but
    // its region is not, so the caller falls back to the gateway zone instead of
    // inventing a location.
    assertThat(regions.findByProvider("ollama").orElseThrow().isDeclared()).isFalse();
    assertThat(regions.findByProvider("openai").orElseThrow().isDeclared()).isFalse();
    assertThat(regions.findByProvider("nope")).isEmpty();
    assertThat(regions.findByProvider(null)).isEmpty();
  }

  @Test
  void selfHostableTypesAreOperatorControlledUnlessTheirRegionIsAssumed() {
    PropertiesProviderRegions regions = regions(Map.of(
        // The zero-config default: your own box, no region declared.
        "ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null, null),
        // A region you chose: still yours.
        "vllm", entry(ProviderProperties.ProviderType.OPENAI_COMPATIBLE,
            "eu-west-3", RegionProvenance.KNOWN, null),
        // OpenAI-compatible but hosted by someone else (OpenRouter…): declaring the
        // region as assumed IS the statement "I do not place this workload".
        "openrouter", entry(ProviderProperties.ProviderType.OPENAI_COMPATIBLE,
            "US-MIDA-PJM", RegionProvenance.ASSUMED, null),
        // A hosted API is never controlled, whatever it declares.
        "anthropic", entry(ProviderProperties.ProviderType.ANTHROPIC,
            "US-MIDA-PJM", RegionProvenance.KNOWN, null)));

    assertThat(controlled(regions, "ollama")).isTrue();
    assertThat(controlled(regions, "vllm")).isTrue();
    assertThat(controlled(regions, "openrouter")).isFalse();
    assertThat(controlled(regions, "anthropic")).isFalse();
  }

  @Test
  void anInstanceWithNoTypeIsNotOperatorControlled() {
    PropertiesProviderRegions regions =
        regions(Map.of("mystery", entry(null, "FR", RegionProvenance.KNOWN, null)));

    assertThat(controlled(regions, "mystery")).isFalse();
  }

  private static boolean controlled(PropertiesProviderRegions regions, String name) {
    return regions.findByProvider(name).orElseThrow().operatorControlled();
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
    return entry(ProviderProperties.ProviderType.OPENAI, region, provenance, pue);
  }

  private static ProviderProperties.ProviderEntry entry(ProviderProperties.ProviderType type,
                                                        String region,
                                                        RegionProvenance provenance,
                                                        Double pue) {
    ProviderProperties.ProviderEntry entry = new ProviderProperties.ProviderEntry();
    entry.setType(type);
    entry.setRegion(region);
    entry.setRegionProvenance(provenance);
    entry.setPue(pue);
    return entry;
  }
}
