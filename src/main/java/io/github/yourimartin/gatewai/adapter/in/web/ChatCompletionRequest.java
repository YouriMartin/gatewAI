package io.github.yourimartin.gatewai.adapter.in.web;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatCompletionRequest(
    String model,
    List<ChatMessage> messages,
    Double temperature,
    Integer maxTokens,
    Double topP,
    Boolean stream,
    Integer n,
    List<String> stop,
    Double presencePenalty,
    Double frequencyPenalty,
    String user
) {
  public ChatCompletionRequest {
    messages = messages == null ? null : List.copyOf(messages);
    stop = stop == null ? null : List.copyOf(stop);
  }
}
