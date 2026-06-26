package com.example.gatewai.application.service;

import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.port.in.ChatCompletionUseCase;
import com.example.gatewai.domain.port.out.LlmClient;

import org.springframework.stereotype.Service;

@Service
class ChatCompletionService implements ChatCompletionUseCase {

  private final LlmClient llmClient;

  ChatCompletionService(LlmClient llmClient) {
    this.llmClient = llmClient;
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    return llmClient.call(request);
  }
}
