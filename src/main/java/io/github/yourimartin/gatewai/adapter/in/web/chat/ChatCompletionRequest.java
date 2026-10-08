package io.github.yourimartin.gatewai.adapter.in.web.chat;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A Chat Completions request (v4 B.1: every field a client SDK commonly sends).
 * Unknown fields are ignored, never rejected. Fields whose shape varies — tools,
 * {@code response_format}, {@code logit_bias} — stay JSON: the gateway only needs
 * to know they are there, and they are read in {@link OpenAiMapper}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionRequest(
    String model,
    List<ChatRequestMessage> messages,
    Double temperature,
    Integer maxTokens,
    Integer maxCompletionTokens,
    Double topP,
    Boolean stream,
    StreamOptions streamOptions,
    Integer n,
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    List<String> stop,
    Double presencePenalty,
    Double frequencyPenalty,
    Long seed,
    String user,
    JsonNode tools,
    JsonNode functions,
    JsonNode responseFormat,
    Boolean logprobs,
    Integer topLogprobs,
    JsonNode logitBias
) {
  public ChatCompletionRequest {
    messages = messages == null ? null : List.copyOf(messages);
    stop = stop == null ? null : List.copyOf(stop);
  }

  /** {@code stream_options}: only {@code include_usage} is honoured. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StreamOptions(Boolean includeUsage) {
  }

  /** True when a streaming client asked for a final usage chunk. */
  boolean includeUsage() {
    return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
  }
}
