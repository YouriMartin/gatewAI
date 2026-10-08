package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.SamplingParameters;

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
    // v4 A.2: both are part of the cache scope, so a deferred job must keep them
    // or its end user would share answers with every other end user of the key.
    if (request.stop() != null) {
      ArrayNode stop = node.putArray("stop");
      request.stop().forEach(stop::add);
    }
    node.put("user", request.user());
    // v4 B.1: forwarded to the egress, so a deferred job must forward them too.
    SamplingParameters sampling = request.sampling();
    putNullableDouble(node, "topP", sampling.topP());
    putNullableDouble(node, "presencePenalty", sampling.presencePenalty());
    putNullableDouble(node, "frequencyPenalty", sampling.frequencyPenalty());
    if (sampling.seed() == null) {
      node.putNull("seed");
    } else {
      node.put("seed", sampling.seed().longValue());
    }
    return MAPPER.writeValueAsString(node);
  }

  static LlmRequest requestFromJson(String json) {
    JsonNode node = MAPPER.readTree(json);
    List<LlmMessage> messages = new ArrayList<>();
    for (JsonNode entry : node.path("messages")) {
      messages.add(new LlmMessage(text(entry, "role"), text(entry, "content")));
    }
    List<String> stop = null;
    if (node.path("stop").isArray()) {
      stop = new ArrayList<>();
      for (JsonNode sequence : node.path("stop")) {
        stop.add(sequence.asString());
      }
    }
    // Jobs stored before v4 A.2 (stop, user) or B.1 (sampling) read back as null.
    JsonNode seed = node.get("seed");
    SamplingParameters sampling = new SamplingParameters(
        nullableDouble(node, "topP"),
        nullableDouble(node, "presencePenalty"),
        nullableDouble(node, "frequencyPenalty"),
        seed == null || seed.isNull() ? null : seed.asLong());
    return new LlmRequest(
        text(node, "model"),
        messages,
        nullableDouble(node, "temperature"),
        nullableInt(node, "maxTokens"),
        stop,
        text(node, "user"),
        sampling,
        Set.of());
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
