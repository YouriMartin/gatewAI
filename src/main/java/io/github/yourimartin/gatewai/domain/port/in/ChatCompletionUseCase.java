package io.github.yourimartin.gatewai.domain.port.in;

import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;

public interface ChatCompletionUseCase {

  LlmResponse complete(LlmRequest request);
}
