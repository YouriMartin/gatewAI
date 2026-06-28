package com.example.gatewai.adapter.in.web;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** OpenAI-shaped streaming chunk (`object: chat.completion.chunk`). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatCompletionChunk(
    String id,
    String object,
    long created,
    String model,
    List<ChunkChoice> choices
) {
  public ChatCompletionChunk {
    choices = choices == null ? null : List.copyOf(choices);
  }
}
