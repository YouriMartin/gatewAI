package io.github.yourimartin.gatewai.domain.model.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.yourimartin.gatewai.domain.model.carbon.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.carbon.EnergySource;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

import org.junit.jupiter.api.Test;

class ModelDefinitionTest {

  private static EnergyProfile modelled(double decode) {
    return new EnergyProfile(0.0, decode, 0.0, EnergySource.MODELLED, false);
  }

  @Test
  void carriesRegistryCostAndEnergyData() {
    ModelDefinition def = new ModelDefinition(
        "claude-sonnet", "anthropic", "claude-sonnet-4-20250514",
        ModelPricing.blended(0.015), modelled(0.6), ModelTier.CLOUD_PREMIUM
    );

    assertEquals("claude-sonnet", def.key());
    assertEquals("anthropic", def.provider());
    assertEquals("claude-sonnet-4-20250514", def.modelId());
    assertEquals(ModelPricing.blended(0.015), def.pricing());
    assertEquals(0.6, def.energy().decodeKwhPer1kCompletionTokens());
    assertEquals(EnergySource.MODELLED, def.energySource());
    assertEquals(ModelTier.CLOUD_PREMIUM, def.tier());
  }

  @Test
  void aModelWithNoEnergyProfileIsExcludedFromScope() {
    // Not "measured as zero": excluded, and rendered as excluded (v3 lot C.1).
    ModelDefinition def = new ModelDefinition(
        "local", "ollama", "qwen2.5:3b", ModelPricing.NONE, null, ModelTier.LOCAL);

    assertEquals(EnergyProfile.NOT_ACCOUNTED, def.energy());
    assertEquals(EnergySource.NOT_ACCOUNTED, def.energySource());
    assertEquals(0.0, def.energy().kwh(10_000, 10_000));
  }

  @Test
  void structuralEquality() {
    ModelDefinition a = new ModelDefinition(
        "llama3", "ollama", "llama3", ModelPricing.NONE, modelled(0.05), ModelTier.LOCAL);
    ModelDefinition b = new ModelDefinition(
        "llama3", "ollama", "llama3", ModelPricing.NONE, modelled(0.05), ModelTier.LOCAL);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void differentFieldsProduceDifferentEquality() {
    ModelDefinition a = new ModelDefinition("claude-sonnet", "anthropic",
        "claude-sonnet-4", ModelPricing.blended(0.015), modelled(0.6), ModelTier.CLOUD_PREMIUM);
    ModelDefinition b = new ModelDefinition("claude-haiku", "anthropic",
        "claude-haiku-4", ModelPricing.blended(0.002), modelled(0.15), ModelTier.CLOUD_ENTRY);

    assertNotEquals(a, b);
  }

  @Test
  void twoModelsDifferingOnlyInEnergyProvenanceAreNotEqual() {
    ModelDefinition modelledEntry = new ModelDefinition("m", "anthropic", "m", ModelPricing.NONE,
        new EnergyProfile(0.0, 0.01, 0.0, EnergySource.MODELLED, false),
        ModelTier.CLOUD_ENTRY);
    ModelDefinition vendorEntry = new ModelDefinition("m", "anthropic", "m", ModelPricing.NONE,
        new EnergyProfile(0.0, 0.01, 0.0, EnergySource.VENDOR_PUBLISHED, false),
        ModelTier.CLOUD_ENTRY);

    assertNotEquals(modelledEntry, vendorEntry);
  }
}
