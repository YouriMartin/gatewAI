package com.example.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ModelDefinitionTest {

  @Test
  void fieldsAreAccessible() {
    ModelDefinition def = new ModelDefinition(
        "claude-sonnet", "anthropic", "claude-sonnet-4-20250514",
        0.015, 0.6, ModelTier.CLOUD_PREMIUM
    );

    assertEquals("claude-sonnet", def.key());
    assertEquals("anthropic", def.provider());
    assertEquals("claude-sonnet-4-20250514", def.modelId());
    assertEquals(0.015, def.costPer1kTokens());
    assertEquals(0.6, def.energyIntensity());
    assertEquals(ModelTier.CLOUD_PREMIUM, def.tier());
  }

  @Test
  void structuralEquality() {
    ModelDefinition a = new ModelDefinition(
        "llama3", "ollama", "llama3", 0.0, 0.05, ModelTier.LOCAL);
    ModelDefinition b = new ModelDefinition(
        "llama3", "ollama", "llama3", 0.0, 0.05, ModelTier.LOCAL);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void differentFieldsProduceDifferentEquality() {
    ModelDefinition a = new ModelDefinition(
        "claude-sonnet", "anthropic", "claude-sonnet-4", 0.015, 0.6,
        ModelTier.CLOUD_PREMIUM);
    ModelDefinition b = new ModelDefinition(
        "claude-haiku", "anthropic", "claude-haiku-4", 0.002, 0.15,
        ModelTier.CLOUD_ENTRY);

    assertNotEquals(a, b);
  }
}
