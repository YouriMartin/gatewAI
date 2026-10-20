package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * How a streamed pass-through ended (v4 B.4). Counted for every stream, not only
 * the failed ones: a disconnect rate is a ratio, and a numerator without its
 * denominator is not one.
 */
public enum PassThroughStreamOutcome {

  /** The upstream ended the stream; the request was accounted. */
  COMPLETED,

  /**
   * The client went away first. The upstream call was cancelled, and the tokens
   * it had already produced — billed by the provider — are not accounted: their
   * count only arrives in the last chunk.
   */
  CLIENT_DISCONNECTED,

  /** The upstream failed after the stream had started. */
  UPSTREAM_FAILED
}
