package io.github.yourimartin.gatewai.domain.port.out;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

public interface ModelRegistry {

  List<ModelDefinition> allModels();

  Optional<ModelDefinition> findByKey(String key);

  Optional<ModelDefinition> findByModelId(String modelId);

  /**
   * The entries of {@code tier} in failover order (ADR 0017): the first is where
   * the tier's new traffic goes, the others where it fails over to.
   */
  List<ModelDefinition> findByTier(ModelTier tier);
}
