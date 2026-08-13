package io.github.yourimartin.gatewai.domain.model;

/**
 * How far the cascade had to go before it could decide (v2 batch 4).
 *
 * <p>Ordered by cost. Each level is more expensive than the one before it and
 * is reached only when the previous one could not answer with enough
 * confidence, so the level recorded on a decision <b>is</b> the price that
 * decision paid: {@link #DETERMINISTIC} is free, {@link #EMBEDDING} costs one
 * local embedding (already computed for the cache on the same request), and
 * {@link #LLM} costs a model call.
 *
 * <p>Which is why the share of requests reaching {@code LLM} — the escalation
 * rate — is the metric that says whether the cascade is worth running.
 */
public enum CascadeLevel {

  /** Code fence or an over-long prompt: no model, no vector, no ambiguity. */
  DETERMINISTIC,

  /** Semantic routes over the request embedding. */
  EMBEDDING,

  /** The classifier model, reached only on an ambiguous prediction set. */
  LLM
}
