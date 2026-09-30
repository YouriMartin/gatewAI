package io.github.yourimartin.gatewai.domain.port.out;

/**
 * How much of a text the embedding model actually sees (ADR 0014, v4 A.2).
 *
 * <p>The embedding truncates at its tokenizer's window, so two long texts that
 * share their opening embed identically whatever follows. The semantic cache
 * asks this before trusting a similarity: past the window, it falls back to an
 * exact match. Counted with the embedding model's own tokenizer, never with a
 * character heuristic.
 */
public interface EmbeddingWindow {

  /** True when the embedding model sees all of {@code text}. */
  boolean fits(String text);
}
