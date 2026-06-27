package com.example.gatewai.domain.port.out;

import java.util.List;
import java.util.Optional;

import com.example.gatewai.domain.model.ModelDefinition;
import com.example.gatewai.domain.model.ModelTier;

public interface ModelRegistry {

  List<ModelDefinition> allModels();

  Optional<ModelDefinition> findByKey(String key);

  List<ModelDefinition> findByTier(ModelTier tier);
}
