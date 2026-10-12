package io.github.yourimartin.gatewai.adapter.in.web.models;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One entry of {@code GET /v1/models}, in the OpenAI shape.
 *
 * <p>{@code created} is a constant 0: the registry records no creation time, and
 * the field must not change across restarts (a picker that sorts or caches by it
 * would reshuffle on every boot). It is not a date and is documented as such.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelView(String id, String object, long created, String ownedBy) {

  /** Who owns the routed alias: the gateway itself, not a provider instance. */
  static final String GATEWAY_OWNER = "gatewai";

  private static final String OBJECT = "model";
  private static final long CREATED = 0L;

  /** The routed alias {@code auto}: "let gatewAI pick the model". */
  static ModelView routed() {
    return new ModelView(ModelDefinition.ROUTED_MODEL_ID, OBJECT, CREATED, GATEWAY_OWNER);
  }

  /** A registry entry: the id that pins it, owned by its provider instance name. */
  static ModelView of(ModelDefinition model) {
    return new ModelView(model.modelId(), OBJECT, CREATED, model.provider());
  }
}
