package com.example.gatewai.domain.port.in;

import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;

public interface ChatCompletionUseCase {

  LlmResponse complete(LlmRequest request);
}
