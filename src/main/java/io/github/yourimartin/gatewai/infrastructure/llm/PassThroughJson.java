package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The only JSON the pass-through path touches (ADR 0016): {@code model} on the
 * way out, {@code usage} on the way back. Everything else in either body is
 * carried as the client and the upstream wrote it.
 */
final class PassThroughJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PassThroughJson() {
  }

  /** {@code body} with {@code model} set to {@code modelId}; every other field kept. */
  static String withModel(String body, String modelId) {
    JsonNode tree = MAPPER.readTree(body);
    if (!(tree instanceof ObjectNode object)) {
      throw new IllegalArgumentException("A Chat Completions request body must be a JSON object.");
    }
    object.put("model", modelId);
    return MAPPER.writeValueAsString(object);
  }

  /**
   * The {@code usage} of a Chat Completions answer, or {@code null} when the
   * upstream reported none or the body is not JSON. A missing total is the sum of
   * the two phases.
   */
  static TokenUsage usage(String body) {
    JsonNode usage;
    try {
      usage = MAPPER.readTree(body).get("usage");
    } catch (JacksonException e) {
      return null;
    }
    if (usage == null || !usage.isObject()) {
      return null;
    }
    int prompt = usage.path("prompt_tokens").asInt(0);
    int completion = usage.path("completion_tokens").asInt(0);
    JsonNode total = usage.get("total_tokens");
    return total == null || !total.isNumber()
        ? TokenUsage.of(prompt, completion)
        : new TokenUsage(prompt, completion, total.asInt());
  }
}
