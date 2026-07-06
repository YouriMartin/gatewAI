package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PropertiesModelRegistryTest {

  private PropertiesModelRegistry registry;

  @BeforeEach
  void setUp() {
    ModelRegistryProperties properties = new ModelRegistryProperties();
    Map<String, ModelRegistryProperties.ModelEntry> entries =
        new LinkedHashMap<>();

    ModelRegistryProperties.ModelEntry sonnet =
        new ModelRegistryProperties.ModelEntry();
    sonnet.setProvider("anthropic");
    sonnet.setModelId("claude-sonnet-4-20250514");
    sonnet.setCostPer1kTokens(0.015);
    sonnet.setEnergyIntensity(0.6);
    sonnet.setTier(ModelTier.CLOUD_PREMIUM);
    entries.put("claude-sonnet", sonnet);

    ModelRegistryProperties.ModelEntry haiku =
        new ModelRegistryProperties.ModelEntry();
    haiku.setProvider("anthropic");
    haiku.setModelId("claude-haiku-4-20250506");
    haiku.setCostPer1kTokens(0.002);
    haiku.setEnergyIntensity(0.15);
    haiku.setTier(ModelTier.CLOUD_ENTRY);
    entries.put("claude-haiku", haiku);

    ModelRegistryProperties.ModelEntry llama =
        new ModelRegistryProperties.ModelEntry();
    llama.setProvider("ollama");
    llama.setModelId("llama3");
    llama.setCostPer1kTokens(0.0);
    llama.setEnergyIntensity(0.05);
    llama.setTier(ModelTier.LOCAL);
    entries.put("llama3", llama);

    properties.setRegistry(entries);
    registry = new PropertiesModelRegistry(properties);
  }

  @Test
  void allModelsReturnsEveryEntry() {
    List<ModelDefinition> all = registry.allModels();

    assertEquals(3, all.size());
  }

  @Test
  void findByKeyReturnsMatchingModel() {
    Optional<ModelDefinition> result = registry.findByKey("claude-haiku");

    assertTrue(result.isPresent());
    assertEquals("anthropic", result.get().provider());
    assertEquals("claude-haiku-4-20250506", result.get().modelId());
    assertEquals(ModelTier.CLOUD_ENTRY, result.get().tier());
  }

  @Test
  void findByKeyReturnsEmptyForUnknownKey() {
    Optional<ModelDefinition> result = registry.findByKey("gpt-4o");

    assertTrue(result.isEmpty());
  }

  @Test
  void findByModelIdReturnsMatchingModel() {
    Optional<ModelDefinition> result =
        registry.findByModelId("claude-haiku-4-20250506");

    assertTrue(result.isPresent());
    assertEquals("claude-haiku", result.get().key());
    assertEquals(ModelTier.CLOUD_ENTRY, result.get().tier());
  }

  @Test
  void findByModelIdReturnsEmptyForUnknownModelId() {
    Optional<ModelDefinition> result = registry.findByModelId("gpt-4o");

    assertTrue(result.isEmpty());
  }

  @Test
  void findByTierReturnsMatchingModels() {
    List<ModelDefinition> premiums =
        registry.findByTier(ModelTier.CLOUD_PREMIUM);

    assertEquals(1, premiums.size());
    assertEquals("claude-sonnet", premiums.getFirst().key());
  }

  @Test
  void findByTierReturnsEmptyWhenNoMatch() {
    ModelRegistryProperties emptyProps = new ModelRegistryProperties();
    PropertiesModelRegistry emptyRegistry =
        new PropertiesModelRegistry(emptyProps);

    List<ModelDefinition> result =
        emptyRegistry.findByTier(ModelTier.CLOUD_PREMIUM);

    assertTrue(result.isEmpty());
  }

  @Test
  void emptyRegistryReturnsEmptyList() {
    ModelRegistryProperties emptyProps = new ModelRegistryProperties();
    PropertiesModelRegistry emptyRegistry =
        new PropertiesModelRegistry(emptyProps);

    assertTrue(emptyRegistry.allModels().isEmpty());
  }

  @Test
  void modelDefinitionCarriesCostAndEnergyData() {
    Optional<ModelDefinition> result = registry.findByKey("llama3");

    assertTrue(result.isPresent());
    assertEquals(0.0, result.get().costPer1kTokens());
    assertEquals(0.05, result.get().energyIntensity());
  }

  @Test
  void modelsListIsImmutable() {
    List<ModelDefinition> all = registry.allModels();

    try {
      all.add(new ModelDefinition(
          "test", "test", "test", 0, 0, ModelTier.LOCAL));
      // If add succeeds, test fails
      assertTrue(false, "List should be immutable");
    } catch (UnsupportedOperationException expected) {
      // Expected: toList() produces an unmodifiable list
    }
  }
}
