package io.github.yourimartin.gatewai.infrastructure.llm;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.port.out.PassThroughClient;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The pass-through upstream of the {@code mock} profile (ADR 0016, v4 B.3): an
 * OpenAI-format echo with no HTTP egress, like {@link MockEchoChatModel} on the
 * chain. Scripted so a tool-calling client can complete a round trip:
 *
 * <ol>
 *   <li>a request with {@code tools} whose last message is not a tool result gets
 *       a call to the first declared tool, with {@code {}} as arguments;</li>
 *   <li>a request whose last message is a tool result gets a final answer that
 *       quotes it;</li>
 *   <li>anything else gets the last user text echoed — as a JSON object when a
 *       JSON {@code response_format} was asked for — once per requested choice.</li>
 * </ol>
 *
 * <p>Token counts are whitespace words, enough to make the accounting non-zero.
 */
@Component
@Profile("mock")
class MockPassThroughClient implements PassThroughClient {

  static final String TOOL_CALL_ID = "call_mock_1";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public Set<PassThroughFeature> ignoredFeatures(ModelDefinition target) {
    return Set.of();
  }

  @Override
  public String describeEndpoint(ModelDefinition target) {
    return "the mock profile's built-in echo";
  }

  @Override
  public PassThroughResponse forward(ModelDefinition target, String body) {
    JsonNode request = MAPPER.readTree(body);
    JsonNode messages = request.path("messages");
    JsonNode last = messages.isArray() && !messages.isEmpty()
        ? messages.get(messages.size() - 1) : MAPPER.createObjectNode();
    boolean lastIsToolResult = isToolResult(last);
    String firstTool = firstToolName(request);

    ObjectNode answer = MAPPER.createObjectNode();
    answer.put("id", "chatcmpl-mock-" + UUID.randomUUID());
    answer.put("object", "chat.completion");
    answer.put("created", Instant.now().getEpochSecond());
    answer.put("model", target.modelId());
    ArrayNode choices = answer.putArray("choices");

    String completion;
    if (firstTool != null && !lastIsToolResult) {
      ObjectNode message = choice(choices, 0, "tool_calls");
      message.putNull("content");
      ObjectNode call = message.putArray("tool_calls").addObject();
      call.put("id", TOOL_CALL_ID);
      call.put("type", "function");
      call.putObject("function").put("name", firstTool).put("arguments", "{}");
      completion = firstTool;
    } else {
      completion = lastIsToolResult
          ? "[mock] tool result: " + text(last.path("content"))
          : "[mock] echo: " + lastUserText(messages);
      if (wantsJson(request)) {
        completion = MAPPER.createObjectNode().put("echo", completion).toString();
      }
      int n = Math.max(1, request.path("n").asInt(1));
      for (int i = 0; i < n; i++) {
        choice(choices, i, "stop").put("content", completion);
      }
    }

    int promptTokens = words(lastUserText(messages));
    int completionTokens = words(completion);
    answer.putObject("usage")
        .put("prompt_tokens", promptTokens)
        .put("completion_tokens", completionTokens)
        .put("total_tokens", promptTokens + completionTokens);

    String json = MAPPER.writeValueAsString(answer);
    return new PassThroughResponse(200, json, target.modelId(), PassThroughJson.usage(json));
  }

  private static ObjectNode choice(ArrayNode choices, int index, String finishReason) {
    ObjectNode choice = choices.addObject();
    choice.put("index", index);
    choice.put("finish_reason", finishReason);
    ObjectNode message = choice.putObject("message");
    message.put("role", "assistant");
    return message;
  }

  private static boolean isToolResult(JsonNode message) {
    String role = message.path("role").asString("");
    return "tool".equals(role) || "function".equals(role);
  }

  private static String firstToolName(JsonNode request) {
    JsonNode tools = request.path("tools");
    if (tools.isArray() && !tools.isEmpty()) {
      return tools.get(0).path("function").path("name").asString(null);
    }
    JsonNode functions = request.path("functions");
    if (functions.isArray() && !functions.isEmpty()) {
      return functions.get(0).path("name").asString(null);
    }
    return null;
  }

  private static boolean wantsJson(JsonNode request) {
    String type = request.path("response_format").path("type").asString("");
    return "json_object".equals(type) || "json_schema".equals(type);
  }

  private static String lastUserText(JsonNode messages) {
    if (!messages.isArray()) {
      return "";
    }
    for (int i = messages.size() - 1; i >= 0; i--) {
      if ("user".equals(messages.get(i).path("role").asString(""))) {
        return text(messages.get(i).path("content"));
      }
    }
    return "";
  }

  /** A string content, or the text parts of an array content, joined by newlines. */
  private static String text(JsonNode content) {
    if (content.isString()) {
      return content.asString();
    }
    StringBuilder text = new StringBuilder();
    if (content.isArray()) {
      for (JsonNode part : content) {
        if ("text".equals(part.path("type").asString(""))) {
          if (!text.isEmpty()) {
            text.append('\n');
          }
          text.append(part.path("text").asString(""));
        }
      }
    }
    return text.toString();
  }

  private static int words(String text) {
    return text == null || text.isBlank() ? 0 : text.strip().split("\\s+").length;
  }
}
