package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.ModelTier;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gatewai.models")
class ModelRegistryProperties {

  private Map<String, ModelEntry> registry = new LinkedHashMap<>();

  Map<String, ModelEntry> getRegistry() {
    return registry;
  }

  void setRegistry(Map<String, ModelEntry> registry) {
    this.registry = registry;
  }

  static class ModelEntry {

    private String provider;
    private String modelId;
    private double costPer1kTokens;
    private double energyIntensity;
    private ModelTier tier;

    String getProvider() {
      return provider;
    }

    void setProvider(String provider) {
      this.provider = provider;
    }

    String getModelId() {
      return modelId;
    }

    void setModelId(String modelId) {
      this.modelId = modelId;
    }

    double getCostPer1kTokens() {
      return costPer1kTokens;
    }

    void setCostPer1kTokens(double costPer1kTokens) {
      this.costPer1kTokens = costPer1kTokens;
    }

    double getEnergyIntensity() {
      return energyIntensity;
    }

    void setEnergyIntensity(double energyIntensity) {
      this.energyIntensity = energyIntensity;
    }

    ModelTier getTier() {
      return tier;
    }

    void setTier(ModelTier tier) {
      this.tier = tier;
    }
  }
}
