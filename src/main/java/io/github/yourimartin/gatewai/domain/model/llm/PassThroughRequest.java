package io.github.yourimartin.gatewai.domain.model.llm;

import java.util.Objects;

/**
 * A request the advisor chain cannot honour, on its way to the pass-through path
 * (ADR 0016, v4 B.3).
 *
 * @param request the typed reading of the request: what the gateway decides on
 *                (the pinned model, the marked features) and hashes for the trace
 * @param body    the request exactly as the client sent it, as JSON text. Opaque
 *                to the domain: only an adapter reads it, and only to rewrite
 *                {@code model}
 */
public record PassThroughRequest(LlmRequest request, String body) {

  public PassThroughRequest {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(body, "body");
    if (!request.needsPassThrough()) {
      throw new IllegalArgumentException(
          "a request the advisor chain can honour must not take the pass-through path");
    }
  }
}
