package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelDefinitionTest {

  @Test
  void fieldsAreAccessible() {
    ModelDefinition def = new ModelDefinition(
        "claude-sonnet", "anthropic", "claude-sonnet-4-20250514",
        0.015, 0.6, EnergySource.MODELLED, ModelTier.CLOUD_PREMIUM
    );

    assertEquals("claude-sonnet", def.key());
    assertEquals("anthropic", def.provider());
    assertEquals("claude-sonnet-4-20250514", def.modelId());
    assertEquals(0.015, def.costPer1kTokens());
    assertEquals(0.6, def.energyIntensity());
    assertEquals(EnergySource.MODELLED, def.energySource());
    assertEquals(ModelTier.CLOUD_PREMIUM, def.tier());
  }

  @Test
  void structuralEquality() {
    ModelDefinition a = new ModelDefinition(
        "llama3", "ollama", "llama3", 0.0, 0.05, EnergySource.MODELLED, ModelTier.LOCAL);
    ModelDefinition b = new ModelDefinition(
        "llama3", "ollama", "llama3", 0.0, 0.05, EnergySource.MODELLED, ModelTier.LOCAL);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void differentFieldsProduceDifferentEquality() {
    ModelDefinition a = new ModelDefinition(
        "claude-sonnet", "anthropic", "claude-sonnet-4", 0.015, 0.6, EnergySource.MODELLED,
        ModelTier.CLOUD_PREMIUM);
    ModelDefinition b = new ModelDefinition(
        "claude-haiku", "anthropic", "claude-haiku-4", 0.002, 0.15, EnergySource.MODELLED,
        ModelTier.CLOUD_ENTRY);

    assertNotEquals(a, b);
  }

  @Test
  void omittedEnergySourceIsDerivedFromTheCoefficient() {
    ModelDefinition metered = new ModelDefinition(
        "claude", "anthropic", "claude-opus-4-8", 0.015, 0.005, null,
        ModelTier.CLOUD_PREMIUM);
    ModelDefinition unmetered = new ModelDefinition(
        "local", "ollama", "qwen2.5:3b", 0.0, 0.0, null, ModelTier.LOCAL);

    assertEquals(EnergySource.MODELLED, metered.energySource());
    assertEquals(EnergySource.NOT_ACCOUNTED, unmetered.energySource());
  }

  @Test
  void anUnaccountedModelCannotAlsoCarryACoefficient() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new ModelDefinition("local", "ollama", "qwen2.5:3b", 0.0, 0.002,
            EnergySource.NOT_ACCOUNTED, ModelTier.LOCAL));

    assertTrue(error.getMessage().contains("not-accounted"), error.getMessage());
  }
}
