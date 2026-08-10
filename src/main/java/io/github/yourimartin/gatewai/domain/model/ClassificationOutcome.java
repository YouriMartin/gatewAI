package io.github.yourimartin.gatewai.domain.model;

/**
 * What a complexity classifier decided, and why (v2 batch 1).
 *
 * <p>Named to avoid colliding with {@code ClassificationResult}, the LLM
 * classifier's Structured Output type, which stays an infrastructure detail of
 * one strategy while this is the port's contract for all of them.
 *
 * @param tier          the tier the request was classified into
 * @param justification why — never null, so an explanation cannot silently go
 *                      missing when the configured strategy changes
 */
public record ClassificationOutcome(ModelTier tier,
                                    ClassificationJustification justification) {

  public ClassificationOutcome {
    if (tier == null) {
      throw new IllegalArgumentException("tier is required");
    }
    if (justification == null) {
      throw new IllegalArgumentException("justification is required");
    }
  }

  /**
   * Re-labels this outcome as a fallback from {@code fallbackFrom}, keeping the
   * tier and wrapping the justification. Used by a strategy that hands over to a
   * cheaper one, so the trace shows both what decided and what stepped aside.
   */
  public ClassificationOutcome asFallbackFrom(
      ClassificationStrategy fallbackFrom,
      ClassificationJustification.FallbackCause cause) {
    return new ClassificationOutcome(tier,
        new ClassificationJustification.Fallback(fallbackFrom, cause,
            justification));
  }
}
