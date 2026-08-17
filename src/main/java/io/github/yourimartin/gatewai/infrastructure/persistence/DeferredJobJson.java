package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps a deferred job's request and result to and from their JSONB columns
 * (v3 lot B.2).
 *
 * <p>Hand-written like {@link JustificationJson} and {@link RoutingConfigJson}:
 * the domain records stay framework-free, and what a stored job actually holds is
 * readable in one place. That matters more here than elsewhere — this is the
 * only place the gateway persists a <b>prompt in clear text</b> outside the
 * vector cache, so the answer to "what is in that column" should be a file, not
 * a set of annotations.
 */
final class DeferredJobJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DeferredJobJson() {
  }

  static String requestToJson(LlmRequest request) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("model", request.model());
    ArrayNode messages = node.putArray("messages");
    if (request.messages() != null) {
      for (LlmMessage message : request.messages()) {
        ObjectNode entry = messages.addObject();
        entry.put("role", message.role());
        entry.put("content", message.content());
      }
    }
    putNullableDouble(node, "temperature", request.temperature());
    putNullableInt(node, "maxTokens", request.maxTokens());
    return MAPPER.writeValueAsString(node);
  }

  static LlmRequest requestFromJson(String json) {
    JsonNode node = MAPPER.readTree(json);
    List<LlmMessage> messages = new ArrayList<>();
    for (JsonNode entry : node.path("messages")) {
      messages.add(new LlmMessage(text(entry, "role"), text(entry, "content")));
    }
    return new LlmRequest(
        text(node, "model"),
        messages,
        nullableDouble(node, "temperature"),
        nullableInt(node, "maxTokens"));
  }

  static String responseToJson(LlmResponse response) {
    if (response == null) {
      return null;
    }
    ObjectNode node = MAPPER.createObjectNode();
    node.put("model", response.model());
    node.put("content", response.content());
    node.put("finishReason", response.finishReason());
    node.put("promptTokens", response.promptTokens());
    node.put("completionTokens", response.completionTokens());
    node.put("totalTokens", response.totalTokens());
    node.put("cacheHit", response.cacheHit());
    return MAPPER.writeValueAsString(node);
  }

  static LlmResponse responseFromJson(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    JsonNode node = MAPPER.readTree(json);
    return new LlmResponse(
        text(node, "model"),
        text(node, "content"),
        text(node, "finishReason"),
        node.path("promptTokens").asInt(),
        node.path("completionTokens").asInt(),
        node.path("totalTokens").asInt(),
        node.path("cacheHit").asBoolean());
  }

  private static void putNullableDouble(ObjectNode node, String field,
                                        Double value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value.doubleValue());
    }
  }

  private static void putNullableInt(ObjectNode node, String field,
                                     Integer value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value.intValue());
    }
  }

  private static Double nullableDouble(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asDouble();
  }

  private static Integer nullableInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asInt();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }
}
