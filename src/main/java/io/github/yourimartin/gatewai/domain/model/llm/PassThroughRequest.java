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
 * @param shadow  true for a shadow client's request (v4 D.1), which takes this
 *                path whether or not the chain could honour it
 */
public record PassThroughRequest(LlmRequest request, String body, boolean shadow) {

  public PassThroughRequest {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(body, "body");
    if (!shadow && !request.needsPassThrough()) {
      throw new IllegalArgumentException(
          "a request the advisor chain can honour must not take the pass-through path");
    }
  }

  /** A request the chain cannot honour, from a client in {@code ENFORCE}. */
  public PassThroughRequest(LlmRequest request, String body) {
    this(request, body, false);
  }

  /** A shadow client's request (v4 D.1). */
  public static PassThroughRequest shadow(LlmRequest request, String body) {
    return new PassThroughRequest(request, body, true);
  }

  /**
   * True when the shadow evaluation applies: a shadow request the chain could
   * have honoured. One it cannot would have been passed through anyway.
   */
  public boolean evaluatedInShadow() {
    return shadow && !request.needsPassThrough();
  }
}
