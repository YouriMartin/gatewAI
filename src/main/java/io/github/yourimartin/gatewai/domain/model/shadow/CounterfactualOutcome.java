package io.github.yourimartin.gatewai.domain.model.shadow;

/**
 * What the chain would have done with a shadow request (v4 D.4, ADR 0020), and so
 * how its counterfactual is priced. Always the chain's own verdict, never a guess.
 */
public enum CounterfactualOutcome {

  /** Served from the cache: no inference, so no cost and no energy. */
  WOULD_HIT,

  /** A miss, sent to the model the router chose: priced at that model. */
  ROUTED,

  /**
   * A request the chain cannot honour (ADR 0016): it would have been passed through
   * anyway, so the counterfactual is what was actually paid.
   */
  PASSED_THROUGH
}
