package io.github.yourimartin.gatewai.adapter.in.web.chat;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One message of a chat request, in every shape the Chat Completions API allows
 * (v4 B.1). {@code content} is a string, an array of parts, or absent (an
 * assistant message that only calls tools); {@link OpenAiMapper} reads it.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequestMessage(
    String role,
    JsonNode content,
    String name,
    List<ChatToolCall> toolCalls,
    ChatFunctionCall functionCall,
    String toolCallId
) {
  public ChatRequestMessage {
    toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
  }

  /** An assistant message's tool call. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatToolCall(String id, String type, ChatFunctionCall function) {
  }

  /**
   * The function a tool call invokes, or a legacy {@code function_call}. The
   * arguments are a JSON string; a client that sends an object is tolerated.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatFunctionCall(String name, JsonNode arguments) {
  }
}
