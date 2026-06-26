package com.example.gatewai.domain.port.out;

import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;

public interface LlmClient {

  LlmResponse call(LlmRequest request);
}
