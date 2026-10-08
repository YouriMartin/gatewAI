package io.github.yourimartin.gatewai.domain.model.llm;

import java.util.List;

/**
 * One message of a chat request.
 *
 * @param role       {@code system}, {@code user}, {@code assistant}, {@code tool} or
 *                   {@code function}; the ingress maps {@code developer} to {@code system}
 * @param content    the message text: the string the client sent, or the text parts
 *                   of a content array joined by {@code \n}; never {@code null}
 * @param parts      every part, in order, but only when one of them is not text —
 *                   empty otherwise, so a text-only array equals the string form
 * @param toolCalls  an assistant message's {@code tool_calls}, or its legacy
 *                   {@code function_call} as a call with no id
 * @param toolCallId the call a {@code tool} message answers
 * @param name       the participant name, or the function a {@code function} message answers
 */
public record LlmMessage(
    String role,
    String content,
    List<ContentPart> parts,
    List<ToolCall> toolCalls,
    String toolCallId,
    String name
) {

  public LlmMessage {
    content = content == null ? "" : content;
    parts = parts == null ? List.of() : List.copyOf(parts);
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  /** A plain text message. */
  public LlmMessage(String role, String content) {
    this(role, content, List.of(), List.of(), null, null);
  }
}
