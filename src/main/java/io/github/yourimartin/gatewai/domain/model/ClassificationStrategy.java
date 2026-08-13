package io.github.yourimartin.gatewai.domain.model;

/**
 * Which complexity-classification strategy the router uses.
 *
 * <p>Lives in the domain because a classification justification names both the
 * strategy that was <em>configured</em> and the one that actually
 * <em>decided</em> — see {@link ClassificationJustification}.
 */
public enum ClassificationStrategy {

  /** Pure heuristics: length, code blocks, keywords. Zero cost, zero latency. */
  HEURISTIC,

  /**
   * Semantic routes: the request embedding is compared to per-route example
   * prompts (max-over-utterances cosine similarity). Language-independent,
   * one local embedding call, no LLM call.
   */
  EMBEDDING,

  /** A small/cheap model returns a structured tier label (Spring AI entity). */
  LLM,

  /**
   * The three above, chained by increasing cost with confidence gates (v2 batch
   * 4): deterministic signals, then semantic routes, then the model — the last
   * one reached only when the routes leave the decision ambiguous. See
   * {@link CascadeLevel}.
   *
   * <p>Never the <em>deciding</em> strategy: a cascade always ends on one of the
   * three, and the justification names which one.
   */
  CASCADE
}
