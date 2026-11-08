package io.github.yourimartin.gatewai.domain.model.shadow;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;

/**
 * The versioned instructions the LLM judge grades a would-hit with (v4 D.2).
 * Every assessment records the {@link #VERSION} it was judged under: change the
 * wording and the version changes with it, so rates judged under two rubrics are
 * never pooled by accident.
 */
public final class JudgeRubric {

  /** Bumped on any change to {@link #SYSTEM} or to how the prompt is laid out. */
  public static final String VERSION = "would-hit-v1";

  static final String SYSTEM = """
      You grade a semantic cache. A user sent the conversation below. A model \
      answered it with ANSWER A. The cache would have served ANSWER B instead, \
      which was written for a similar earlier request.

      Question: would ANSWER B have been an acceptable answer to this conversation, \
      as good for the user as ANSWER A? Wording and length may differ; facts, \
      instructions and the thing asked about must not.

      Reply with exactly one word: YES, NO or UNSURE. UNSURE only when the \
      conversation does not let you decide.""";

  private JudgeRubric() {
  }

  /**
   * The judge's prompt for one would-hit: the rubric, then the conversation as the
   * client sent it, then both answers. Built in memory, sent once, never stored.
   *
   * @param conversation the request's messages
   * @param received     the answer the client received (A)
   * @param cached       the answer the cache would have served (B)
   */
  public static List<LlmMessage> prompt(List<LlmMessage> conversation, String received,
                                        String cached) {
    StringBuilder body = new StringBuilder("CONVERSATION\n");
    for (LlmMessage message : conversation) {
      body.append('[').append(message.role()).append("] ")
          .append(message.content() == null ? "" : message.content()).append('\n');
    }
    body.append("\nANSWER A\n").append(received)
        .append("\n\nANSWER B\n").append(cached)
        .append("\n\nYES, NO or UNSURE?");
    List<LlmMessage> prompt = new ArrayList<>(2);
    prompt.add(new LlmMessage("system", SYSTEM));
    prompt.add(new LlmMessage("user", body.toString()));
    return List.copyOf(prompt);
  }
}
