package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.springframework.stereotype.Component;

@Component
class PropertiesModelRegistry implements ModelRegistry {

  private final List<ModelDefinition> models;
  /** Every entry in failover order (ADR 0017): {@code priority} first, then binding order. */
  private final List<ModelDefinition> failoverOrder;

  PropertiesModelRegistry(ModelRegistryProperties properties) {
    List<Map.Entry<String, ModelRegistryProperties.ModelEntry>> entries =
        List.copyOf(properties.getRegistry().entrySet());
    this.models = entries.stream()
        .map(PropertiesModelRegistry::toModelDefinition)
        .toList();
    // A stable sort: entries with equal (or no) priority keep binding order.
    this.failoverOrder = entries.stream()
        .sorted(Comparator.comparing(
            (Map.Entry<String, ModelRegistryProperties.ModelEntry> e) -> e.getValue().getPriority(),
            Comparator.nullsLast(Comparator.naturalOrder())))
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
    return failoverOrder.stream()
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
        e.getEnergy().toProfile(),
        e.getTier()
    );
  }
}
