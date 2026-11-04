package io.github.yourimartin.gatewai.domain.model.context;

import java.util.Objects;

import io.github.yourimartin.gatewai.domain.model.client.ClientMode;

/**
 * Immutable context propagated through the advisor chain via a ScopedValue.
 * Carries the client/tenant identity and trace correlation ID.
 *
 * <p>This record has zero framework dependencies — it relies only on
 * JDK types.
 *
 * @param clientId identifier of the API client / tenant
 * @param traceId  correlation ID for distributed tracing
 * @param mode     the client's mode (v4 D.1): every decision taken under this
 *                 context is recorded with it
 */
public record RequestContext(String clientId, String traceId, ClientMode mode) {

  /**
   * JDK ScopedValue carrier for the current request context.
   * Bound at the ingress layer, readable anywhere in the
   * virtual-thread call stack without explicit parameter passing.
   */
  public static final ScopedValue<RequestContext> CURRENT =
      ScopedValue.newInstance();

  public RequestContext {
    Objects.requireNonNull(mode, "mode");
  }

  /** A context of a client in {@link ClientMode#ENFORCE}. */
  public RequestContext(String clientId, String traceId) {
    this(clientId, traceId, ClientMode.ENFORCE);
  }

  /**
   * The mode of the request being served: {@link ClientMode#ENFORCE} when no
   * context is bound — a scheduled job, a test — since nothing then runs in shadow.
   */
  public static ClientMode currentMode() {
    return CURRENT.isBound() ? CURRENT.get().mode() : ClientMode.ENFORCE;
  }

  /** True when the request being served is a shadow client's (v4 D.1). */
  public static boolean shadow() {
    return currentMode() == ClientMode.SHADOW;
  }
}
