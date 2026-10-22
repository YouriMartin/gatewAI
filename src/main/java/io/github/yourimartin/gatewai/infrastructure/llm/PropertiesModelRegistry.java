package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class PropertiesModelRegistry implements ModelRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(PropertiesModelRegistry.class);

  private final List<ModelDefinition> models;
  /** Every entry in failover order (ADR 0017): {@code priority} first, then binding order. */
  private final List<ModelDefinition> failoverOrder;

  PropertiesModelRegistry(ModelRegistryProperties properties) {
    List<Map.Entry<String, ModelRegistryProperties.ModelEntry>> entries =
        List.copyOf(properties.getRegistry().entrySet());
    List<ModelDefinition> definitions = new ArrayList<>();
    entries.forEach(entry -> definitions.add(toModelDefinition(entry)));
    this.models = List.copyOf(definitions);
    // A stable sort: entries with equal (or no) priority keep binding order.
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      order.add(i);
    }
    order.sort(Comparator.comparing((Integer i) -> entries.get(i).getValue().getPriority(),
        Comparator.nullsLast(Comparator.naturalOrder())));
    this.failoverOrder = order.stream().map(definitions::get).toList();
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
        pricing(entry.getKey(), e),
        e.getEnergy().toProfile(),
        e.getTier()
    );
  }

  /**
   * The entry's price (v4 B.6): split rates under {@code pricing.*}, else the
   * deprecated {@code cost-per-1k-tokens} as a blended rate, else no price. Both
   * at once is ambiguous and fails startup; a split price needs both its input and
   * its output rate, because a missing one would read as free.
   */
  private static ModelPricing pricing(String key, ModelRegistryProperties.ModelEntry entry) {
    ModelRegistryProperties.Pricing split = entry.getPricing();
    String prefix = "gatewai.models.registry." + key;
    if (split != null && split.isDeclared()) {
      if (entry.getCostPer1kTokens() != null) {
        throw new IllegalStateException(prefix + " declares both pricing.* and the deprecated"
            + " cost-per-1k-tokens. Keep pricing.* and remove " + prefix
            + ".cost-per-1k-tokens.");
      }
      if (split.getInputPer1m() == null || split.getOutputPer1m() == null) {
        throw new IllegalStateException(prefix + ".pricing needs both input-per-1m and"
            + " output-per-1m (cached-input-per-1m is optional): a missing rate would read"
            + " as free.");
      }
      return checked(prefix, () -> ModelPricing.split(split.getInputPer1m(),
          split.getOutputPer1m(), split.getCachedInputPer1m()));
    }
    if (entry.getCostPer1kTokens() != null) {
      LOG.warn("{}.cost-per-1k-tokens is deprecated and will be removed in the next release:"
          + " it is priced as BLENDED, one rate for input and output, which overstates"
          + " input-heavy traffic. Declare {}.pricing.input-per-1m and .output-per-1m"
          + " (per million tokens, as providers publish them) instead.", prefix, prefix);
      return checked(prefix, () -> ModelPricing.blended(entry.getCostPer1kTokens()));
    }
    return ModelPricing.NONE;
  }

  private static ModelPricing checked(String prefix,
                                      Supplier<ModelPricing> price) {
    try {
      return price.get();
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(prefix + ": " + e.getMessage(), e);
    }
  }
}
