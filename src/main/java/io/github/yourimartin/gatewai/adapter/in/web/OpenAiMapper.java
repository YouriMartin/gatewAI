package io.github.yourimartin.gatewai.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.LlmStreamChunk;

/** Maps between the OpenAI-shaped web DTOs and the domain request/response. */
final class OpenAiMapper {

  private OpenAiMapper() {
  }

  static LlmRequest toLlmRequest(ChatCompletionRequest request) {
    if (request.messages() == null || request.messages().isEmpty()) {
      throw new IllegalArgumentException(
          "'messages' is a required property and must not be empty.");
    }
    List<LlmMessage> messages = request.messages().stream()
        .map(message -> new LlmMessage(message.role(), message.content()))
        .toList();
    return new LlmRequest(request.model(), messages,
        request.temperature(), request.maxTokens());
  }

  /** Maps a domain stream chunk to an OpenAI {@code chat.completion.chunk}. */
  static ChatCompletionChunk toChunk(String id, long created, LlmStreamChunk chunk) {
    String finishReason = chunk.finishReason() == null || chunk.finishReason().isBlank()
        ? null : chunk.finishReason();
    ChatMessage delta = new ChatMessage("assistant", chunk.contentDelta());
    return new ChatCompletionChunk(
        id, "chat.completion.chunk", created, chunk.model(),
        List.of(new ChunkChoice(0, delta, finishReason)));
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
