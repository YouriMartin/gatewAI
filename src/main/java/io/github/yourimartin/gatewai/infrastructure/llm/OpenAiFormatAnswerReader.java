package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.llm.FinishReason;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.port.out.RelayedAnswerReader;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the answer of an OpenAI-format Chat Completions body or stream (v4 D.1):
 * {@code choices[0].message.content} on a call, the concatenated
 * {@code choices[0].delta.content} on a stream, and the finish reason of either.
 * The pass-through clients relay OpenAI format whatever the provider, so one
 * reader serves them all.
 */
@Component
class OpenAiFormatAnswerReader implements RelayedAnswerReader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public Optional<RelayedAnswer> read(PassThroughResponse response) {
    JsonNode choice = firstChoice(parse(response.body()));
    if (choice == null) {
      return Optional.empty();
    }
    JsonNode content = choice.path("message").get("content");
    if (content == null || !content.isString()) {
      return Optional.empty();
    }
    return Optional.of(new RelayedAnswer(response.model(), content.asString(),
        finishReason(choice), response.usage()));
  }

  @Override
  public StreamedAnswer streamed(String model) {
    return new Accumulator(model);
  }

  /** Concatenates the deltas of one stream, keeping only its text. */
  private static final class Accumulator implements StreamedAnswer {

    private final String model;
    private final StringBuilder text = new StringBuilder();
    private String finishReason;
    private boolean sawChoice;

    Accumulator(String model) {
      this.model = model;
    }

    @Override
    public void accept(String event) {
      if (event == null || !event.contains("\"choices\"")) {
        return;
      }
      JsonNode choice = firstChoice(parse(event));
      if (choice == null) {
        return;
      }
      sawChoice = true;
      JsonNode content = choice.path("delta").get("content");
      if (content != null && content.isString()) {
        text.append(content.asString());
      }
      String reason = finishReason(choice);
      if (reason != null) {
        finishReason = reason;
      }
    }

    @Override
    public Optional<RelayedAnswer> answer(TokenUsage usage) {
      return sawChoice
          ? Optional.of(new RelayedAnswer(model, text.toString(), finishReason, usage))
          : Optional.empty();
    }
  }

  private static JsonNode parse(String json) {
    if (json == null) {
      return null;
    }
    try {
      return MAPPER.readTree(json);
    } catch (JacksonException e) {
      return null;
    }
  }

  /** The first choice; null when there is none to read. */
  private static JsonNode firstChoice(JsonNode tree) {
    JsonNode choices = tree == null ? null : tree.get("choices");
    if (choices == null || !choices.isArray() || choices.isEmpty()) {
      return null;
    }
    JsonNode first = choices.get(0);
    return first.isObject() ? first : null;
  }

  private static String finishReason(JsonNode choice) {
    JsonNode reason = choice.get("finish_reason");
    return reason == null || !reason.isString() ? null : FinishReason.toOpenAi(reason.asString());
  }
}
