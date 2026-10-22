package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * How a request's cost was priced (v4 B.6), stored on every {@code request_log}
 * row and printed beside every cost figure.
 */
public enum PricingBasis {

  /**
   * Input, output and cached input at their own per-million rates, the unit
   * providers publish.
   */
  SPLIT,

  /**
   * One rate per 1000 tokens of any kind, the pre-v4 {@code cost-per-1k-tokens}.
   * An average that overstates input-heavy traffic. Every row written before v4
   * B.6 is on this basis.
   */
  BLENDED,

  /** No price declared: the cost is zero because it is not priced, not because it is free. */
  NONE
}
