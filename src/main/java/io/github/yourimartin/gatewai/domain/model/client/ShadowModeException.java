package io.github.yourimartin.gatewai.domain.model.client;

import io.github.yourimartin.gatewai.domain.model.context.RequestContext;

/**
 * A shadow client asked for something only the advisor chain serves (v4 D.1): an
 * asynchronous job, an MCP tool call. A shadow client must receive the upstream
 * answer of the model it names, so the chain never serves it — it is refused,
 * not silently enforced.
 */
public class ShadowModeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ShadowModeException(String what) {
    super(what + " is not available to a client in shadow mode: only"
        + " /v1/chat/completions is, and it serves the model the request names."
        + " An admin can put the client back in ENFORCE mode.");
  }

  /** Refuses {@code what} when the request being served is a shadow client's. */
  public static void refuseInShadow(String what) {
    if (RequestContext.shadow()) {
      throw new ShadowModeException(what);
    }
  }
}
