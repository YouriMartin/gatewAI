package io.github.yourimartin.gatewai.domain.model;

/**
 * Whether an occlusion attribution could be computed, and if not, why
 * (v2 batch 7).
 *
 * <p>A status rather than an exception for the cases that are <b>expected</b>:
 * asking why a prompt was routed while the heuristic strategy is configured is a
 * reasonable question with a definite answer — "nothing about this decision was
 * about similarity". A genuine embedding failure is not in this enum and is left
 * to propagate: this runs on demand, off the request path, and an admin asking
 * for an explanation is owed an error rather than an empty list.
 */
public enum AttributionStatus {

  /** Segments were scored; the report carries them. */
  COMPUTED,

  /**
   * The configured strategy does not decide by similarity, so there is no
   * similarity to attribute. Occlusion explains semantic routes, not keywords
   * and not a model's own reasoning.
   */
  NOT_APPLICABLE_STRATEGY,

  /** The strategy uses routes, but none is configured. */
  NO_ROUTES_CONFIGURED,

  /** Nothing to segment. */
  EMPTY_PROMPT
}
