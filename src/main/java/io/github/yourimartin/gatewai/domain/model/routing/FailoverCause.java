package io.github.yourimartin.gatewai.domain.model.routing;

import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;

/**
 * Why a request left a registry entry for the next one of its tier (ADR 0017,
 * v4 B.5). Only transient failures are listed: any other upstream answer would
 * be the same on the next entry, so it never fails over.
 */
public enum FailoverCause {

  /** No connection could be made, or it dropped before an answer. */
  CONNECTION_FAILED,

  /** The instance's {@code connect-timeout} or {@code read-timeout} elapsed. */
  TIMEOUT,

  /** HTTP 429. */
  RATE_LIMITED,

  /** HTTP 5xx, Anthropic's 529 included. */
  SERVER_ERROR;

  /**
   * The cause a failure fails over for, or empty when it must reach the client
   * as it is: a 4xx other than 429 says the request or the gateway's
   * configuration is wrong, and the next entry would get the same request.
   */
  public static Optional<FailoverCause> of(UpstreamFailureException failure) {
    return Optional.ofNullable(switch (failure.kind()) {
      case TIMEOUT -> TIMEOUT;
      case RATE_LIMITED -> RATE_LIMITED;
      case UNAVAILABLE -> failure.upstreamStatus() == 0 ? CONNECTION_FAILED : SERVER_ERROR;
      case REJECTED, MISCONFIGURED -> null;
    });
  }
}
