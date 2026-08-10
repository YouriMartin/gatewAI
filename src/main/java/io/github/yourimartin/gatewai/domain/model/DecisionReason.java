package io.github.yourimartin.gatewai.domain.model;

/**
 * Why a request ended up on the tier it did (v2 batch 2) — the one-word summary
 * of a {@link ClassificationJustification}, cheap to filter and aggregate on.
 *
 * <p>Only reasons the gateway can actually produce are listed. {@code
 * AMBIGUOUS_ESCALATED} (cascade) and {@code CLIENT_PINNED} arrive with batch 4,
 * together with the behaviour that raises them: a reason code that can never
 * fire is worse than no reason code.
 */
public enum DecisionReason {

  /** A strategy classified the request and its answer was used. */
  MATCH,

  /** No route reached the similarity threshold; the heuristic decided. */
  BELOW_THRESHOLD_FALLBACK,

  /** The configured strategy failed or produced nothing usable. */
  ERROR_FALLBACK,

  /**
   * The request was classified, but the registry holds no model for that tier,
   * so the router passed the request through untouched.
   */
  NO_MODEL_FOR_TIER;

  /**
   * Summarizes a justification. Anything that is not a hand-over is a
   * {@link #MATCH} — including a heuristic decision when the heuristic is the
   * configured strategy.
   */
  public static DecisionReason from(ClassificationJustification justification) {
    return switch (justification) {
      case ClassificationJustification.Fallback fallback ->
          fromCause(fallback.cause());
      case ClassificationJustification.FailSafe failSafe ->
          fromCause(failSafe.cause());
      case ClassificationJustification.Heuristic ignored -> MATCH;
      case ClassificationJustification.Embedding ignored -> MATCH;
      case ClassificationJustification.Llm ignored -> MATCH;
    };
  }

  private static DecisionReason fromCause(
      ClassificationJustification.FallbackCause cause) {
    return cause == ClassificationJustification.FallbackCause.BELOW_THRESHOLD
        ? BELOW_THRESHOLD_FALLBACK
        : ERROR_FALLBACK;
  }
}
