package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.EnergySource;
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
    sonnet.getEnergy().setDecodeKwhPer1kCompletionTokens(0.6);
    sonnet.setTier(ModelTier.CLOUD_PREMIUM);
    entries.put("claude-sonnet", sonnet);

    ModelRegistryProperties.ModelEntry haiku =
        new ModelRegistryProperties.ModelEntry();
    haiku.setProvider("anthropic");
    haiku.setModelId("claude-haiku-4-20250506");
    haiku.setCostPer1kTokens(0.002);
    haiku.getEnergy().setDecodeKwhPer1kCompletionTokens(0.15);
    haiku.setTier(ModelTier.CLOUD_ENTRY);
    entries.put("claude-haiku", haiku);

    ModelRegistryProperties.ModelEntry llama =
        new ModelRegistryProperties.ModelEntry();
    llama.setProvider("ollama");
    llama.setModelId("llama3");
    llama.setCostPer1kTokens(0.0);
    llama.getEnergy().setDecodeKwhPer1kCompletionTokens(0.05);
    llama.getEnergy().setSource(EnergySource.MODELLED);
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
    assertEquals(0.05, result.get().energy().decodeKwhPer1kCompletionTokens());
    assertEquals(EnergySource.MODELLED, result.get().energySource());
  }

  @Test
  void bindsThePrefillDecodeSplitAndItsOverheadFlag() {
    ModelRegistryProperties.ModelEntry vendor =
        new ModelRegistryProperties.ModelEntry();
    vendor.setProvider("gemini");
    vendor.setModelId("gemini-2.5-flash");
    vendor.getEnergy().setPrefillKwhPer1kPromptTokens(0.0001);
    vendor.getEnergy().setDecodeKwhPer1kCompletionTokens(0.002);
    vendor.getEnergy().setFixedKwhPerRequest(0.00024);
    vendor.getEnergy().setSource(EnergySource.VENDOR_PUBLISHED);
    vendor.getEnergy().setIncludesDatacenterOverhead(true);
    vendor.setTier(ModelTier.CLOUD_ENTRY);
    ModelRegistryProperties properties = new ModelRegistryProperties();
    properties.setRegistry(new LinkedHashMap<>(Map.of("gemini-entry", vendor)));

    ModelDefinition definition = new PropertiesModelRegistry(properties)
        .findByKey("gemini-entry").orElseThrow();

    assertEquals(0.0001, definition.energy().prefillKwhPer1kPromptTokens());
    assertEquals(0.002, definition.energy().decodeKwhPer1kCompletionTokens());
    assertEquals(0.00024, definition.energy().fixedKwhPerRequest());
    assertEquals(EnergySource.VENDOR_PUBLISHED, definition.energySource());
    assertTrue(definition.energy().includesDatacenterOverhead());
  }

  @Test
  void anEntryWithoutAnEnergySourceDerivesItFromItsCoefficient() {
    // The two cloud entries above declare no energy-source: a coefficient makes
    // them modelled, while a zero would make them excluded from scope.
    ModelRegistryProperties.ModelEntry local =
        new ModelRegistryProperties.ModelEntry();
    local.setProvider("ollama");
    local.setModelId("qwen2.5:3b");
    local.setCostPer1kTokens(0.0);
    local.getEnergy().setDecodeKwhPer1kCompletionTokens(0.0);
    local.setTier(ModelTier.LOCAL);
    ModelRegistryProperties properties = new ModelRegistryProperties();
    properties.setRegistry(new LinkedHashMap<>(Map.of("local-large", local)));

    PropertiesModelRegistry localRegistry = new PropertiesModelRegistry(properties);

    assertEquals(EnergySource.MODELLED,
        registry.findByKey("claude-sonnet").orElseThrow().energySource());
    assertEquals(EnergySource.NOT_ACCOUNTED,
        localRegistry.findByKey("local-large").orElseThrow().energySource());
  }

  @Test
  void modelsListIsImmutable() {
    List<ModelDefinition> all = registry.allModels();

    try {
      all.add(new ModelDefinition(
          "test", "test", "test", 0, EnergyProfile.NOT_ACCOUNTED, ModelTier.LOCAL));
      // If add succeeds, test fails
      assertTrue(false, "List should be immutable");
    } catch (UnsupportedOperationException expected) {
      // Expected: toList() produces an unmodifiable list
    }
  }
}
