package com.example.gatewai.adapter.in.web;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatCompletionResponse(
    String id,
    String object,
    long created,
    String model,
    List<ChatChoice> choices,
    TokenUsage usage,
    String systemFingerprint
) {
  public ChatCompletionResponse {
    choices = choices == null ? null : List.copyOf(choices);
  }
}
