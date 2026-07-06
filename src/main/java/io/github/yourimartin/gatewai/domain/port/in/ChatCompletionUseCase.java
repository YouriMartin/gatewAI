package io.github.yourimartin.gatewai.domain.port.in;

import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;

public interface ChatCompletionUseCase {

  LlmResponse complete(LlmRequest request);
}
