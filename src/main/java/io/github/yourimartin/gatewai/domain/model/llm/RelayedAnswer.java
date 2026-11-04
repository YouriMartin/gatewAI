package io.github.yourimartin.gatewai.domain.model.llm;

import java.util.Objects;

/**
 * The answer a shadow client received, read back from what was relayed to it
 * (v4 D.1), so the advisor chain can decide on it without calling a model again.
 * Held in memory for the shadow evaluation only: never persisted as such — the
 * cache stores it under its own rules, as it would have stored the chain's.
 *
 * @param model        the registry model id that produced it
 * @param text         the assistant text, possibly empty
 * @param finishReason the OpenAI finish reason, null when the upstream sent none
 * @param usage        the token counts the upstream reported, null when none
 */
public record RelayedAnswer(String model, String text, String finishReason, TokenUsage usage) {

  public RelayedAnswer {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(text, "text");
  }
}
