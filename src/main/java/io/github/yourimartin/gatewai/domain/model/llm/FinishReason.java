package io.github.yourimartin.gatewai.domain.model.llm;

import java.util.Locale;

/**
 * Maps a provider's finish reason to the OpenAI value (ADR 0014, v4 A.2).
 *
 * <p>Spring AI passes finish reasons through as each provider spells them —
 * {@code STOP} from the OpenAI model, {@code end_turn} from Anthropic,
 * {@code stop} from Ollama — and they used to reach OpenAI-format clients that
 * way. The cache needs one vocabulary to decide what is safe to store (only an
 * answer that ended normally), and the ingress promised the OpenAI one all
 * along. Both use this.
 */
public final class FinishReason {

  /** The answer ended normally. The only reason the cache stores. */
  public static final String STOP = "stop";
  /** The answer was cut by a token limit. */
  public static final String LENGTH = "length";
  /** The model asked for a tool. */
  public static final String TOOL_CALLS = "tool_calls";
  /** The provider withheld or cut the answer. */
  public static final String CONTENT_FILTER = "content_filter";

  private FinishReason() {
  }

  /**
   * The OpenAI finish reason for a provider value.
   *
   * @return the OpenAI value; null for a null or blank input; an unknown value
   *     unchanged, since inventing a mapping would hide it
   */
  public static String toOpenAi(String providerValue) {
    if (providerValue == null || providerValue.isBlank()) {
      return null;
    }
    return switch (providerValue.toLowerCase(Locale.ROOT)) {
      case "stop", "end_turn", "stop_sequence" -> STOP;
      case "length", "max_tokens" -> LENGTH;
      case "tool_calls", "tool_use", "function_call" -> TOOL_CALLS;
      case "content_filter", "refusal" -> CONTENT_FILTER;
      default -> providerValue;
    };
  }
}
