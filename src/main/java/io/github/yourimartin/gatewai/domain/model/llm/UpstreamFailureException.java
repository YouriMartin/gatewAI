package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * A provider answered with an error, or did not answer (ADR 0016, v4 B.3). Since
 * B.5 both paths raise it, the advisor chain included (ADR 0017), and its kind is
 * what decides whether a request fails over. Carries what the client is told,
 * never what the upstream said: its body may name the gateway's configuration.
 */
public class UpstreamFailureException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What went wrong, in terms of who has to fix it. */
  public enum Kind {
    /** 400, 413 or 422: the upstream refused the request itself. The client's to fix. */
    REJECTED,
    /** 429: the upstream is throttling. Retry later. */
    RATE_LIMITED,
    /** 401, 403, 404 or another 4xx: the gateway's credentials or model id are wrong. */
    MISCONFIGURED,
    /** 5xx, or no connection at all. */
    UNAVAILABLE,
    /** The instance's {@code connect-timeout} or {@code read-timeout} elapsed. */
    TIMEOUT
  }

  private final Kind kind;
  private final int upstreamStatus;

  public UpstreamFailureException(Kind kind, int upstreamStatus, String message) {
    this(kind, upstreamStatus, message, null);
  }

  public UpstreamFailureException(Kind kind, int upstreamStatus, String message,
                                  Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.upstreamStatus = upstreamStatus;
  }

  /** Classifies an upstream error status. */
  public static UpstreamFailureException forStatus(int status, String modelId) {
    Kind kind = switch (status) {
      case 400, 413, 422 -> Kind.REJECTED;
      case 429 -> Kind.RATE_LIMITED;
      default -> status >= 500 ? Kind.UNAVAILABLE : Kind.MISCONFIGURED;
    };
    return new UpstreamFailureException(kind, status,
        "Upstream of model '" + modelId + "' answered HTTP " + status);
  }

  public Kind kind() {
    return kind;
  }

  /** The upstream HTTP status, or 0 when the upstream never answered. */
  public int upstreamStatus() {
    return upstreamStatus;
  }
}
