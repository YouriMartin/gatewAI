package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ModelDefinitionTest {

  private static EnergyProfile modelled(double decode) {
    return new EnergyProfile(0.0, decode, 0.0, EnergySource.MODELLED, false);
  }

  @Test
  void carriesRegistryCostAndEnergyData() {
    ModelDefinition def = new ModelDefinition(
        "claude-sonnet", "anthropic", "claude-sonnet-4-20250514",
        0.015, modelled(0.6), ModelTier.CLOUD_PREMIUM
    );

    assertEquals("claude-sonnet", def.key());
    assertEquals("anthropic", def.provider());
    assertEquals("claude-sonnet-4-20250514", def.modelId());
    assertEquals(0.015, def.costPer1kTokens());
    assertEquals(0.6, def.energy().decodeKwhPer1kCompletionTokens());
    assertEquals(EnergySource.MODELLED, def.energySource());
    assertEquals(ModelTier.CLOUD_PREMIUM, def.tier());
  }

  @Test
  void aModelWithNoEnergyProfileIsExcludedFromScope() {
    // Not "measured as zero": excluded, and rendered as excluded (v3 lot C.1).
    ModelDefinition def = new ModelDefinition(
        "local", "ollama", "qwen2.5:3b", 0.0, null, ModelTier.LOCAL);

    assertEquals(EnergyProfile.NOT_ACCOUNTED, def.energy());
    assertEquals(EnergySource.NOT_ACCOUNTED, def.energySource());
    assertEquals(0.0, def.energy().kwh(10_000, 10_000));
  }

  @Test
  void structuralEquality() {
    ModelDefinition a = new ModelDefinition(
        "llama3", "ollama", "llama3", 0.0, modelled(0.05), ModelTier.LOCAL);
    ModelDefinition b = new ModelDefinition(
        "llama3", "ollama", "llama3", 0.0, modelled(0.05), ModelTier.LOCAL);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void differentFieldsProduceDifferentEquality() {
    ModelDefinition a = new ModelDefinition("claude-sonnet", "anthropic",
        "claude-sonnet-4", 0.015, modelled(0.6), ModelTier.CLOUD_PREMIUM);
    ModelDefinition b = new ModelDefinition("claude-haiku", "anthropic",
        "claude-haiku-4", 0.002, modelled(0.15), ModelTier.CLOUD_ENTRY);

    assertNotEquals(a, b);
  }

  @Test
  void twoModelsDifferingOnlyInEnergyProvenanceAreNotEqual() {
    ModelDefinition modelledEntry = new ModelDefinition("m", "anthropic", "m", 0.0,
        new EnergyProfile(0.0, 0.01, 0.0, EnergySource.MODELLED, false),
        ModelTier.CLOUD_ENTRY);
    ModelDefinition vendorEntry = new ModelDefinition("m", "anthropic", "m", 0.0,
        new EnergyProfile(0.0, 0.01, 0.0, EnergySource.VENDOR_PUBLISHED, false),
        ModelTier.CLOUD_ENTRY);

    assertNotEquals(modelledEntry, vendorEntry);
  }
}
