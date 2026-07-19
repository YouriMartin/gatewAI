package io.github.yourimartin.gatewai.infrastructure.llm;

/**
 * Selects which complexity-classification strategy the router uses.
 */
enum ClassifierStrategy {

  /** Pure heuristics: length, code blocks, keywords. Zero cost, zero latency. */
  HEURISTIC,

  /**
   * Semantic routes: the request embedding is compared to per-route example
   * prompts (max-over-utterances cosine similarity). Language-independent,
   * one local embedding call, no LLM call.
   */
  EMBEDDING,

  /** A small/cheap model returns a structured tier label (Spring AI entity). */
  LLM
}
