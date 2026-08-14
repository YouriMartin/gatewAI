package io.github.yourimartin.gatewai.domain.model;

/**
 * Whether counterfactuals could be computed, and if not, why (v2 batch 8).
 *
 * <p>Same reasoning as {@link AttributionStatus}: the expected cases are
 * statuses, because "there is no alternative outcome here" is an answer, while
 * an empty list looks like a failure. A genuine embedding failure is not in this
 * enum and propagates — this runs on demand, off the request path.
 */
public enum CounterfactualStatus {

  /** Alternatives were ranked; the report carries them. */
  COMPUTED,

  /**
   * The configured strategy does not decide by similarity, so no route was
   * beaten by another: there is no ranking to read backwards.
   */
  NOT_APPLICABLE_STRATEGY,

  /** The strategy uses routes, but none is configured. */
  NO_ROUTES_CONFIGURED,

  /** Nothing to compare. */
  EMPTY_PROMPT,

  /**
   * Routes exist and one won, but every other route maps to the tier that won
   * anyway — so no wording of the request would have changed where it went.
   * The chosen route is still reported; only the alternatives are empty.
   */
  NO_ALTERNATIVE_TIER,

  /**
   * A stored decision was asked to explain itself and the prompt is gone
   * (v2 batch 9). Ranking routes means embedding the request, and only its hash
   * was kept. The route scores the decision was <em>taken</em> with are still in
   * the trace's justification — what cannot be rebuilt is the comparison
   * against today's routes.
   */
  PROMPT_UNAVAILABLE
}
