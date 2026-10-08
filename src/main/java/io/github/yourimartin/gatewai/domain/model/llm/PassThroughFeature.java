package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * A request feature the advisor chain cannot honour (v4 B.1). A request that uses
 * one is marked at the ingress; it must be forwarded as-is to a provider that
 * speaks the OpenAI format (ADR 0016, batch B.3), never served by the chain with
 * the feature silently dropped.
 */
public enum PassThroughFeature {

  /** {@code tools} or the legacy {@code functions}. */
  TOOLS("tools", "tools"),
  /** {@code tool}/{@code function} messages, or assistant {@code tool_calls}/{@code function_call}. */
  TOOL_MESSAGES("tool calls and tool results in messages", "messages"),
  /** {@code image_url} content parts. */
  IMAGE_INPUT("image_url content parts", "messages"),
  /** {@code input_audio} content parts. */
  AUDIO_INPUT("input_audio content parts", "messages"),
  /** {@code file} content parts. */
  FILE_INPUT("file content parts", "messages"),
  /** Content parts of a type this gateway does not know. */
  OTHER_CONTENT("content parts other than text", "messages"),
  /** A {@code response_format} other than {@code text}. */
  RESPONSE_FORMAT("response_format", "response_format"),
  /** {@code n} greater than 1. */
  MULTIPLE_CHOICES("n greater than 1", "n"),
  /** {@code logprobs} or {@code top_logprobs}. */
  LOGPROBS("logprobs", "logprobs"),
  /** A non-empty {@code logit_bias}. */
  LOGIT_BIAS("logit_bias", "logit_bias");

  private final String description;
  private final String param;

  PassThroughFeature(String description, String param) {
    this.description = description;
    this.param = param;
  }

  /** What the client sent, in the words of the OpenAI API. */
  public String description() {
    return description;
  }

  /** The top-level request field that carries the feature. */
  public String param() {
    return param;
  }
}
