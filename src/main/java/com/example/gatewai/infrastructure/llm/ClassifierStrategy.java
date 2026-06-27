package com.example.gatewai.infrastructure.llm;

/**
 * Selects which complexity-classification strategy the router uses.
 */
enum ClassifierStrategy {

  /** Pure heuristics: length, code blocks, keywords. Zero cost, zero latency. */
  HEURISTIC,

  /** A small/cheap model returns a structured tier label (Spring AI entity). */
  LLM
}
