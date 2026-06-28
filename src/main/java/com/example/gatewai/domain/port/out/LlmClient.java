package com.example.gatewai.domain.port.out;

import java.util.function.Consumer;

import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.model.LlmStreamChunk;

public interface LlmClient {

  LlmResponse call(LlmRequest request);

  /**
   * Streams the response (Phase 7.5). Blocks until the stream completes, calling
   * {@code onChunk} for each piece. Callback-based to keep this port free of
   * reactive types.
   */
  void stream(LlmRequest request, Consumer<LlmStreamChunk> onChunk);
}
