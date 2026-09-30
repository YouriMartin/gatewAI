package io.github.yourimartin.gatewai.domain.model.llm;

import java.util.List;

/**
 * A chat request, as the ingress hands it to the use cases.
 *
 * @param stop stop sequences; part of the cache scope (ADR 0014), and forwarded
 *             to the model
 * @param user the OpenAI {@code user} field: the caller's end user, part of the
 *             cache scope so one API key's end users never share an answer
 */
public record LlmRequest(
    String model,
    List<LlmMessage> messages,
    Double temperature,
    Integer maxTokens,
    List<String> stop,
    String user
) {

  /**
   * Key under which the LLM adapter hands {@link #user()} to the advisor chain.
   * Defined in the domain to keep the cache and llm adapters decoupled.
   */
  public static final String END_USER_CONTEXT_KEY = "gatewai.end_user";

  public LlmRequest {
    messages = messages == null ? null : List.copyOf(messages);
    stop = stop == null ? null : List.copyOf(stop);
  }

  /** A request with no stop sequences and no end user. */
  public LlmRequest(String model, List<LlmMessage> messages, Double temperature,
                    Integer maxTokens) {
    this(model, messages, temperature, maxTokens, null, null);
  }
}
