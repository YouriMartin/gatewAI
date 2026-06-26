package com.example.gatewai.domain.model;

import java.util.List;

public record LlmRequest(
    String model,
    List<LlmMessage> messages,
    Double temperature,
    Integer maxTokens
) {
  public LlmRequest {
    messages = messages == null ? null : List.copyOf(messages);
  }
}
