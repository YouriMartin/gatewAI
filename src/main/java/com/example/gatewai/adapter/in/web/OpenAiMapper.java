package com.example.gatewai.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.gatewai.domain.model.LlmMessage;
import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;

/** Maps between the OpenAI-shaped web DTOs and the domain request/response. */
final class OpenAiMapper {

  private OpenAiMapper() {
  }

  static LlmRequest toLlmRequest(ChatCompletionRequest request) {
    List<LlmMessage> messages = request.messages().stream()
        .map(message -> new LlmMessage(message.role(), message.content()))
        .toList();
    return new LlmRequest(request.model(), messages,
        request.temperature(), request.maxTokens());
  }

  static ChatCompletionResponse toCompletionResponse(LlmResponse response) {
    return new ChatCompletionResponse(
        "chatcmpl-" + UUID.randomUUID(),
        "chat.completion",
        Instant.now().getEpochSecond(),
        response.model(),
        List.of(new ChatChoice(
            0,
            new ChatMessage("assistant", response.content()),
            response.finishReason()
        )),
        new TokenUsage(
            response.promptTokens(),
            response.completionTokens(),
            response.totalTokens()
        ),
        null
    );
  }
}
