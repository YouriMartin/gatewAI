package io.github.yourimartin.gatewai.domain.port.out;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;

public interface ModelRegistry {

  List<ModelDefinition> allModels();

  Optional<ModelDefinition> findByKey(String key);

  Optional<ModelDefinition> findByModelId(String modelId);

  List<ModelDefinition> findByTier(ModelTier tier);
}
