package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.springframework.stereotype.Component;

@Component
class PropertiesModelRegistry implements ModelRegistry {

  private final List<ModelDefinition> models;

  PropertiesModelRegistry(ModelRegistryProperties properties) {
    this.models = properties.getRegistry().entrySet().stream()
        .map(PropertiesModelRegistry::toModelDefinition)
        .toList();
  }

  @Override
  public List<ModelDefinition> allModels() {
    return List.copyOf(models);
  }

  @Override
  public Optional<ModelDefinition> findByKey(String key) {
    return models.stream()
        .filter(m -> m.key().equals(key))
        .findFirst();
  }

  @Override
  public Optional<ModelDefinition> findByModelId(String modelId) {
    return models.stream()
        .filter(m -> m.modelId().equals(modelId))
        .findFirst();
  }

  @Override
  public List<ModelDefinition> findByTier(ModelTier tier) {
    return models.stream()
        .filter(m -> m.tier() == tier)
        .toList();
  }

  private static ModelDefinition toModelDefinition(
      Map.Entry<String, ModelRegistryProperties.ModelEntry> entry) {
    ModelRegistryProperties.ModelEntry e = entry.getValue();
    return new ModelDefinition(
        entry.getKey(),
        e.getProvider(),
        e.getModelId(),
        e.getCostPer1kTokens(),
        e.getEnergyIntensity(),
        e.getTier()
    );
  }
}
