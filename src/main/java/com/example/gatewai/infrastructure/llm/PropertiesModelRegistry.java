package com.example.gatewai.infrastructure.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.gatewai.domain.model.ModelDefinition;
import com.example.gatewai.domain.model.ModelTier;
import com.example.gatewai.domain.port.out.ModelRegistry;

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
    return models;
  }

  @Override
  public Optional<ModelDefinition> findByKey(String key) {
    return models.stream()
        .filter(m -> m.key().equals(key))
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
