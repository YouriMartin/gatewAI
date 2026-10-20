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
    try {
      return usageOf(MAPPER.readTree(body).get("usage"));
    } catch (JacksonException e) {
      return null;
    }
  }

  private static TokenUsage usageOf(JsonNode usage) {
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

  /**
   * {@code body} for a streamed call (v4 B.4): {@code model} rewritten and
   * {@code stream_options.include_usage} turned on, the other stream options kept.
   * Without it OpenAI-format servers send no usage at all on a stream, and the
   * request could not be accounted.
   */
  static String forStream(String body, String modelId) {
    JsonNode tree = MAPPER.readTree(body);
    if (!(tree instanceof ObjectNode object)) {
      throw new IllegalArgumentException("A Chat Completions request body must be a JSON object.");
    }
    object.put("model", modelId);
    object.put("stream", true);
    JsonNode options = object.get("stream_options");
    ObjectNode streamOptions = options instanceof ObjectNode existing
        ? existing : object.putObject("stream_options");
    streamOptions.put("include_usage", true);
    return MAPPER.writeValueAsString(object);
  }

  /** True when the client itself asked for the final usage chunk. */
  static boolean clientWantsUsage(String body) {
    return MAPPER.readTree(body).path("stream_options").path("include_usage").asBoolean(false);
  }

  /**
   * What a stream event says about usage.
   *
   * @param usage     the usage it carries, null when none
   * @param usageOnly true for the chunk {@code include_usage} adds at the end: no
   *                  choices, only the usage — the one to drop when the client did
   *                  not ask for it
   */
  record ChunkUsage(TokenUsage usage, boolean usageOnly) {

    static final ChunkUsage NONE = new ChunkUsage(null, false);
  }

  /**
   * Reads the usage of one stream event's {@code data}. A chunk that does not even
   * name a {@code usage} field — most of them — is not parsed.
   */
  static ChunkUsage chunkUsage(String data) {
    if (data == null || !data.contains("\"usage\"")) {
      return ChunkUsage.NONE;
    }
    JsonNode chunk;
    try {
      chunk = MAPPER.readTree(data);
    } catch (JacksonException e) {
      return ChunkUsage.NONE;
    }
    TokenUsage usage = usageOf(chunk.get("usage"));
    if (usage == null) {
      return ChunkUsage.NONE;
    }
    JsonNode choices = chunk.get("choices");
    return new ChunkUsage(usage, choices == null || choices.isNull() || choices.isEmpty());
  }
}
