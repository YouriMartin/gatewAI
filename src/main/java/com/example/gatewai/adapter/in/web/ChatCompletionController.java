package com.example.gatewai.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.gatewai.domain.model.LlmMessage;
import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.port.in.ChatCompletionUseCase;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatCompletionController {

  private final ChatCompletionUseCase useCase;

  ChatCompletionController(ChatCompletionUseCase useCase) {
    this.useCase = useCase;
  }

  @PostMapping("/v1/chat/completions")
  ChatCompletionResponse complete(@RequestBody ChatCompletionRequest request) {
    List<LlmMessage> messages = request.messages().stream()
        .map(m -> new LlmMessage(m.role(), m.content()))
        .toList();
    LlmRequest llmRequest = new LlmRequest(
        request.model(), messages, request.temperature(), request.maxTokens());

    LlmResponse llmResponse = useCase.complete(llmRequest);

    return new ChatCompletionResponse(
        "chatcmpl-" + UUID.randomUUID(),
        "chat.completion",
        Instant.now().getEpochSecond(),
        llmResponse.model(),
        List.of(new ChatChoice(
            0,
            new ChatMessage("assistant", llmResponse.content()),
            llmResponse.finishReason()
        )),
        new TokenUsage(
            llmResponse.promptTokens(),
            llmResponse.completionTokens(),
            llmResponse.totalTokens()
        ),
        null
    );
  }
}
