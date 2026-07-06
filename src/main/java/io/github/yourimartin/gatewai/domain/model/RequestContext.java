package io.github.yourimartin.gatewai.domain.model;

/**
 * Immutable context propagated through the advisor chain via a ScopedValue.
 * Carries the client/tenant identity and trace correlation ID.
 *
 * <p>This record has zero framework dependencies — it relies only on
 * JDK types.
 *
 * @param clientId identifier of the API client / tenant
 * @param traceId  correlation ID for distributed tracing
 */
public record RequestContext(String clientId, String traceId) {

  /**
   * JDK ScopedValue carrier for the current request context.
   * Bound at the ingress layer, readable anywhere in the
   * virtual-thread call stack without explicit parameter passing.
   */
  public static final ScopedValue<RequestContext> CURRENT =
      ScopedValue.newInstance();
}
