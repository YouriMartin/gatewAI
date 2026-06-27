package com.example.gatewai.adapter.in.web;

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
    LlmResponse llmResponse =
        useCase.complete(OpenAiMapper.toLlmRequest(request));
    return OpenAiMapper.toCompletionResponse(llmResponse);
  }
}
