package io.github.yourimartin.gatewai.domain.model;

/**
 * Why a request ended up on the tier it did (v2 batch 2) — the one-word summary
 * of a {@link ClassificationJustification}, cheap to filter and aggregate on.
 *
 * <p>Only reasons the gateway can actually produce are listed, which is why
 * {@code AMBIGUOUS_ESCALATED} and {@code CLIENT_PINNED} arrived in v2 batch 4
 * with the behaviour that raises them — the cascade and client pinning — rather
 * than sitting here as codes that could never fire.
 */
public enum DecisionReason {

  /** A strategy classified the request and its answer was used. */
  MATCH,

  /**
   * The cascade's semantic routes left the tier open, so the classifier model
   * was called and its answer used (v2 batch 4).
   */
  AMBIGUOUS_ESCALATED,

  /**
   * The client named a registered model id, so nothing was classified: the
   * gateway is a plain proxy for callers that already know what they want
   * (v2 batch 4).
   */
  CLIENT_PINNED,

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
   *
   * <p>A cascade reports the reason of the level that decided, except when it
   * decided <em>because</em> it escalated: reaching the model on an ambiguous
   * prediction set is the cascade's own reason, and the one worth aggregating.
   */
  public static DecisionReason from(ClassificationJustification justification) {
    return switch (justification) {
      case ClassificationJustification.Cascade cascade -> fromCascade(cascade);
      case ClassificationJustification.Fallback fallback ->
          fromCause(fallback.cause());
      case ClassificationJustification.FailSafe failSafe ->
          fromCause(failSafe.cause());
      case ClassificationJustification.Heuristic ignored -> MATCH;
      case ClassificationJustification.Embedding ignored -> MATCH;
      case ClassificationJustification.Llm ignored -> MATCH;
    };
  }

  /**
   * An escalation that ends on the model is {@code AMBIGUOUS_ESCALATED}; one
   * that ends on a hand-over — the model was called and failed — keeps the
   * hand-over's reason, because a degraded decision must not be reported as a
   * successful escalation.
   */
  private static DecisionReason fromCascade(
      ClassificationJustification.Cascade cascade) {
    DecisionReason decided = from(cascade.decided());
    return cascade.level() == CascadeLevel.LLM && decided == MATCH
        ? AMBIGUOUS_ESCALATED : decided;
  }

  private static DecisionReason fromCause(
      ClassificationJustification.FallbackCause cause) {
    return cause == ClassificationJustification.FallbackCause.BELOW_THRESHOLD
        ? BELOW_THRESHOLD_FALLBACK
        : ERROR_FALLBACK;
  }
}
