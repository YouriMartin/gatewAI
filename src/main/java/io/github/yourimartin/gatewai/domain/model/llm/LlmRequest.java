package io.github.yourimartin.gatewai.domain.model.llm;

import java.util.List;
import java.util.Set;

/**
 * A chat request, as the ingress hands it to the use cases.
 *
 * @param maxTokens   {@code max_completion_tokens}, or {@code max_tokens} when only
 *                    that one is sent
 * @param stop        stop sequences; part of the cache scope (ADR 0014), and forwarded
 *                    to the model
 * @param user        the OpenAI {@code user} field: the caller's end user, part of the
 *                    cache scope so one API key's end users never share an answer
 * @param sampling    the remaining parameters forwarded to the egress (v4 B.1)
 * @param passThrough what the advisor chain cannot honour in this request (v4 B.1);
 *                    empty for a request the chain serves
 */
public record LlmRequest(
    String model,
    List<LlmMessage> messages,
    Double temperature,
    Integer maxTokens,
    List<String> stop,
    String user,
    SamplingParameters sampling,
    Set<PassThroughFeature> passThrough
) {

  /**
   * Key under which the LLM adapter hands {@link #user()} to the advisor chain.
   * Defined in the domain to keep the cache and llm adapters decoupled.
   */
  public static final String END_USER_CONTEXT_KEY = "gatewai.end_user";

  public LlmRequest {
    messages = messages == null ? null : List.copyOf(messages);
    stop = stop == null ? null : List.copyOf(stop);
    sampling = sampling == null ? SamplingParameters.NONE : sampling;
    passThrough = passThrough == null || passThrough.isEmpty()
        ? Set.of() : Set.copyOf(passThrough);
  }

  /** A request with no other sampling parameter, which the chain can serve. */
  public LlmRequest(String model, List<LlmMessage> messages, Double temperature,
                    Integer maxTokens, List<String> stop, String user) {
    this(model, messages, temperature, maxTokens, stop, user, SamplingParameters.NONE, Set.of());
  }

  /** A request with no stop sequences and no end user. */
  public LlmRequest(String model, List<LlmMessage> messages, Double temperature,
                    Integer maxTokens) {
    this(model, messages, temperature, maxTokens, null, null);
  }

  /** True when the request uses a feature the advisor chain cannot honour. */
  public boolean needsPassThrough() {
    return !passThrough.isEmpty();
  }

  /**
   * Refuses a request the advisor chain cannot honour. Every use case that hands
   * a request to the chain calls this first, so a marked request is never served
   * with its features silently dropped.
   *
   * @throws UnsupportedFeatureException when the request needs pass-through
   */
  public void requireServableByChain() {
    if (needsPassThrough()) {
      throw new UnsupportedFeatureException(passThrough);
    }
  }
}
