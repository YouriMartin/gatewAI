package io.github.yourimartin.gatewai.adapter.in.web.chat;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * OpenAI-shaped streaming chunk (`object: chat.completion.chunk`). {@code usage}
 * is set only on the final chunk a client asks for with
 * {@code stream_options.include_usage}, and omitted everywhere else.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatCompletionChunk(
    String id,
    String object,
    long created,
    String model,
    List<ChunkChoice> choices,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    TokenUsage usage
) {
  public ChatCompletionChunk {
    choices = choices == null ? null : List.copyOf(choices);
  }
}
