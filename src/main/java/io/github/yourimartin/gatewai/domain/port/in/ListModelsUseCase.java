package io.github.yourimartin.gatewai.domain.port.in;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;

/**
 * The models a client may name in a request (v4 B.2): every registry entry, in
 * declaration order. The routed alias {@link ModelDefinition#ROUTED_MODEL_ID} is
 * not an entry — it is how the ingress spells "no pin", so the adapter lists it.
 */
public interface ListModelsUseCase {

  List<ModelDefinition> models();

  Optional<ModelDefinition> find(String modelId);
}
