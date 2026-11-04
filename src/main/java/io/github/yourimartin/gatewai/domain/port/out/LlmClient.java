package io.github.yourimartin.gatewai.domain.port.out;

import java.util.function.Consumer;

import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.LlmStreamChunk;
import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;

public interface LlmClient {

  LlmResponse call(LlmRequest request);

  /**
   * Streams the response (Phase 7.5). Blocks until the stream completes, calling
   * {@code onChunk} for each piece. Callback-based to keep this port free of
   * reactive types.
   */
  void stream(LlmRequest request, Consumer<LlmStreamChunk> onChunk);

  /**
   * Runs {@code request} through the advisor chain as {@link #call} would, except
   * that no model is called: where the egress would answer, {@code answer} does
   * (v4 D.1). The cache and the router decide, record and store exactly as they
   * would have for this answer.
   *
   * @return what the chain returned: the cached answer on a would-hit, otherwise
   *         {@code answer}, with the cache outcome
   */
  LlmResponse replay(LlmRequest request, RelayedAnswer answer);
}
