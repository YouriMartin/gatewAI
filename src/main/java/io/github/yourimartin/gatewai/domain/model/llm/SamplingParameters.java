package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * Sampling parameters forwarded to the egress where its provider supports them
 * (v4 B.1). Not part of the cache scope, like temperature (ADR 0014).
 *
 * @param seed any integer a client sends; a provider whose seed is a 32-bit
 *             integer receives it only when it fits
 */
public record SamplingParameters(
    Double topP,
    Double presencePenalty,
    Double frequencyPenalty,
    Long seed
) {

  /** No parameter set: the provider's defaults apply. */
  public static final SamplingParameters NONE = new SamplingParameters(null, null, null, null);
}
