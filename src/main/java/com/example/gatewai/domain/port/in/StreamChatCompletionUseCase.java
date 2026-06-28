package com.example.gatewai.domain.port.in;

import java.util.function.Consumer;

import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmStreamChunk;

/**
 * Streaming variant of {@link ChatCompletionUseCase} (Phase 7.5). Callback-based
 * on purpose: the port stays free of reactive types, so the domain/application
 * layers don't depend on Reactor — the streaming bridge lives in the adapters.
 * The call blocks until the stream completes, invoking {@code onChunk} per piece.
 */
public interface StreamChatCompletionUseCase {

  void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> onChunk);
}
