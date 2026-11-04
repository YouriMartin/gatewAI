package io.github.yourimartin.gatewai.domain.port.out;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;

/**
 * Asks one registry model to grade a would-hit (v4 D.2). A direct call: never
 * through the advisor chain, whose cache could answer the judge with a cached
 * verdict and whose router could send it to another model.
 */
public interface AnswerJudge {

  /**
   * Sends {@code prompt} to {@code modelId} and returns its reply.
   *
   * @throws RuntimeException when the call fails; the caller records the failure
   */
  Reply judge(String modelId, List<LlmMessage> prompt);

  /**
   * The judge's reply.
   *
   * @param text  what it answered
   * @param usage the tokens it reported, {@link TokenUsage#NONE} when none
   */
  record Reply(String text, TokenUsage usage) {
  }
}
