package io.github.yourimartin.gatewai.domain.model;

/**
 * Thrown when a request targets a model id that is not declared in the model
 * registry, or whose registry entry references a provider with no configured
 * egress. There is deliberately no fallback provider: every routable model must
 * be a registry entry backed by a configured provider instance, so a miss is a
 * client error, not a silent reroute to another vendor.
 */
public class UnknownModelException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnknownModelException(String message) {
    super(message);
  }
}
