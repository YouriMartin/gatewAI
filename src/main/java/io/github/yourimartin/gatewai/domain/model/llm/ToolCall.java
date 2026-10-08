package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * A tool call an assistant message made earlier in the conversation (v4 B.1).
 *
 * @param id        the call id a later {@code tool} message answers; {@code null}
 *                  for a legacy {@code function_call}, which has none
 * @param type      the call type, {@code function} for every call OpenAI documents
 * @param name      the function name
 * @param arguments the arguments, as the JSON string the model produced
 */
public record ToolCall(String id, String type, String name, String arguments) {
}
