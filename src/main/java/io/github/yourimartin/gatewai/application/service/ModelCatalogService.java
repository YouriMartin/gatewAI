package io.github.yourimartin.gatewai.application.service;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.port.in.ListModelsUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.springframework.stereotype.Service;

/**
 * Lists the model registry for discovery (v4 B.2). A listed id is exactly what
 * pins: the lookup is the one the router uses ({@code findByModelId}).
 */
@Service
class ModelCatalogService implements ListModelsUseCase {

  private final ModelRegistry modelRegistry;

  ModelCatalogService(ModelRegistry modelRegistry) {
    this.modelRegistry = modelRegistry;
  }

  @Override
  public List<ModelDefinition> models() {
    return modelRegistry.allModels();
  }

  @Override
  public Optional<ModelDefinition> find(String modelId) {
    return modelRegistry.findByModelId(modelId);
  }
}
