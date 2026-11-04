package io.github.yourimartin.gatewai.domain.model.decision;

/**
 * What became of one shadow client's request (v4 D.1), as counted by
 * {@code gatewai_shadow_decisions_total{outcome}}.
 */
public enum ShadowOutcome {

  /** The cache would have answered. */
  WOULD_HIT,

  /** The cache would have missed, and the router would have picked a model. */
  MISS,

  /** The cache would not have been consulted: a pass-through request, or a bypass. */
  BYPASS,

  /** Not evaluated: the shadow work was at its concurrency limit. */
  DROPPED,

  /** The evaluation failed. The client's answer was not affected. */
  FAILED
}
