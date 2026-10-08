package io.github.yourimartin.gatewai.adapter.in.web.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.llm.ContentPart;
import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.LlmStreamChunk;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.SamplingParameters;
import io.github.yourimartin.gatewai.domain.model.llm.ToolCall;

import tools.jackson.databind.JsonNode;

/**
 * Maps between the OpenAI-shaped web DTOs and the domain request/response.
 *
 * <p>Since v4 B.1 this is where the ingress reads every message shape a client
 * SDK sends — string or array {@code content}, the {@code developer} role, tool
 * calls and tool results — into typed domain parts, and where a request is
 * marked with the {@link PassThroughFeature features} the advisor chain cannot
 * honour. JSON never leaves this package.
 */
final class OpenAiMapper {

  private static final String TEXT = "text";

  private OpenAiMapper() {
  }

  static LlmRequest toLlmRequest(ChatCompletionRequest request) {
    if (request.messages() == null || request.messages().isEmpty()) {
      throw new IllegalArgumentException(
          "'messages' is a required property and must not be empty.");
    }
    Set<PassThroughFeature> passThrough = EnumSet.noneOf(PassThroughFeature.class);
    List<LlmMessage> messages = new ArrayList<>(request.messages().size());
    for (int i = 0; i < request.messages().size(); i++) {
      messages.add(toLlmMessage(request.messages().get(i), i, passThrough));
    }
    markRequestFeatures(request, passThrough);

    Integer maxTokens = request.maxCompletionTokens() != null
        ? request.maxCompletionTokens() : request.maxTokens();
    SamplingParameters sampling = new SamplingParameters(request.topP(),
        request.presencePenalty(), request.frequencyPenalty(), request.seed());
    return new LlmRequest(request.model(), messages, request.temperature(), maxTokens,
        request.stop(), request.user(), sampling, passThrough);
  }

  private static LlmMessage toLlmMessage(ChatRequestMessage message, int index,
                                         Set<PassThroughFeature> passThrough) {
    if (message == null) {
      throw new IllegalArgumentException("messages[" + index + "] must be an object.");
    }
    String role = role(message.role(), index);
    List<ToolCall> toolCalls = toolCalls(message);
    if (!toolCalls.isEmpty() || "tool".equals(role) || "function".equals(role)) {
      passThrough.add(PassThroughFeature.TOOL_MESSAGES);
    }

    JsonNode content = message.content();
    if (content == null || content.isNull() || content.isMissingNode()) {
      return new LlmMessage(role, "", List.of(), toolCalls, message.toolCallId(), message.name());
    }
    if (content.isString()) {
      return new LlmMessage(role, content.asString(), List.of(), toolCalls,
          message.toolCallId(), message.name());
    }
    if (!content.isArray()) {
      throw new IllegalArgumentException("messages[" + index
          + "].content must be a string or an array of content parts.");
    }
    List<ContentPart> parts = new ArrayList<>(content.size());
    for (JsonNode part : content) {
      parts.add(toPart(part, index, passThrough));
    }
    // A text-only array is the string form, in parts: same text, same request.
    boolean textOnly = parts.stream().allMatch(ContentPart.Text.class::isInstance);
    return new LlmMessage(role, joinText(parts), textOnly ? List.of() : parts, toolCalls,
        message.toolCallId(), message.name());
  }

  /** The domain role: {@code developer} is the newer name of {@code system}. */
  private static String role(String role, int index) {
    if (role == null) {
      throw new IllegalArgumentException("messages[" + index + "].role is required.");
    }
    return switch (role) {
      case "system", "developer" -> "system";
      case "user", "assistant", "tool", "function" -> role;
      default -> throw new IllegalArgumentException("messages[" + index + "].role '" + role
          + "' is not one of system, developer, user, assistant, tool, function.");
    };
  }

  private static List<ToolCall> toolCalls(ChatRequestMessage message) {
    List<ToolCall> calls = new ArrayList<>();
    if (message.toolCalls() != null) {
      for (ChatRequestMessage.ChatToolCall call : message.toolCalls()) {
        if (call == null) {
          continue;
        }
        ChatRequestMessage.ChatFunctionCall function = call.function();
        calls.add(new ToolCall(call.id(), call.type() == null ? "function" : call.type(),
            function == null ? null : function.name(),
            function == null ? null : arguments(function.arguments())));
      }
    }
    ChatRequestMessage.ChatFunctionCall legacy = message.functionCall();
    if (legacy != null) {
      calls.add(new ToolCall(null, "function", legacy.name(), arguments(legacy.arguments())));
    }
    return calls;
  }

  private static String arguments(JsonNode arguments) {
    if (arguments == null || arguments.isNull()) {
      return null;
    }
    return arguments.isString() ? arguments.asString() : arguments.toString();
  }

  private static ContentPart toPart(JsonNode part, int index, Set<PassThroughFeature> passThrough) {
    if (part.isString()) {
      // Not in the spec, but harmless: a bare string in the array is a text part.
      return new ContentPart.Text(part.asString());
    }
    if (!part.isObject()) {
      throw new IllegalArgumentException("messages[" + index
          + "].content parts must be objects with a 'type'.");
    }
    String type = textOf(part, "type");
    if (type == null) {
      throw new IllegalArgumentException("messages[" + index
          + "].content parts must be objects with a 'type'.");
    }
    switch (type) {
      case TEXT -> {
        String text = textOf(part, TEXT);
        return new ContentPart.Text(text == null ? "" : text);
      }
      case "image_url" -> {
        passThrough.add(PassThroughFeature.IMAGE_INPUT);
        JsonNode image = part.path("image_url");
        // Early clients sent the URL as a bare string rather than {url, detail}.
        return image.isString()
            ? new ContentPart.ImageUrl(image.asString(), null)
            : new ContentPart.ImageUrl(textOf(image, "url"), textOf(image, "detail"));
      }
      case "input_audio" -> {
        passThrough.add(PassThroughFeature.AUDIO_INPUT);
        JsonNode audio = part.path("input_audio");
        return new ContentPart.InputAudio(textOf(audio, "data"), textOf(audio, "format"));
      }
      case "file" -> {
        passThrough.add(PassThroughFeature.FILE_INPUT);
        JsonNode file = part.path("file");
        return new ContentPart.File(textOf(file, "file_id"), textOf(file, "filename"),
            textOf(file, "file_data"));
      }
      default -> {
        passThrough.add(PassThroughFeature.OTHER_CONTENT);
        return new ContentPart.Other(type, part.toString());
      }
    }
  }

  private static String joinText(List<ContentPart> parts) {
    StringBuilder text = new StringBuilder();
    for (ContentPart part : parts) {
      if (part instanceof ContentPart.Text(String value)) {
        if (!text.isEmpty()) {
          text.append('\n');
        }
        text.append(value);
      }
    }
    return text.toString();
  }

  /** Marks the request-level fields the advisor chain cannot honour. */
  private static void markRequestFeatures(ChatCompletionRequest request,
                                          Set<PassThroughFeature> passThrough) {
    if (isNonEmpty(request.tools()) || isNonEmpty(request.functions())) {
      passThrough.add(PassThroughFeature.TOOLS);
    }
    JsonNode responseFormat = request.responseFormat();
    if (responseFormat != null && !responseFormat.isNull()
        && !TEXT.equals(textOf(responseFormat, "type"))) {
      passThrough.add(PassThroughFeature.RESPONSE_FORMAT);
    }
    if (request.n() != null && request.n() > 1) {
      passThrough.add(PassThroughFeature.MULTIPLE_CHOICES);
    }
    if (Boolean.TRUE.equals(request.logprobs())
        || request.topLogprobs() != null && request.topLogprobs() > 0) {
      passThrough.add(PassThroughFeature.LOGPROBS);
    }
    if (isNonEmpty(request.logitBias())) {
      passThrough.add(PassThroughFeature.LOGIT_BIAS);
    }
  }

  private static boolean isNonEmpty(JsonNode node) {
    return node != null && !node.isNull() && !(node.isContainer() && node.isEmpty());
  }

  private static String textOf(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  /** Maps a domain stream chunk to an OpenAI {@code chat.completion.chunk}. */
  static ChatCompletionChunk toChunk(String id, long created, LlmStreamChunk chunk) {
    String finishReason = chunk.finishReason() == null || chunk.finishReason().isBlank()
        ? null : chunk.finishReason();
    ChatMessage delta = new ChatMessage("assistant", chunk.contentDelta());
    return new ChatCompletionChunk(
        id, "chat.completion.chunk", created, chunk.model(),
        List.of(new ChunkChoice(0, delta, finishReason)), null);
  }

  /**
   * The chunk a client that set {@code stream_options.include_usage} receives
   * last, before {@code [DONE]}: no choices, the usage of the whole answer.
   */
  static ChatCompletionChunk toUsageChunk(String id, long created, LlmStreamChunk usage) {
    return new ChatCompletionChunk(
        id, "chat.completion.chunk", created, usage == null ? null : usage.model(),
        List.of(),
        usage == null ? new TokenUsage(0, 0, 0) : new TokenUsage(
            usage.promptTokens(), usage.completionTokens(), usage.totalTokens()));
  }

  static ChatCompletionResponse toCompletionResponse(LlmResponse response) {
    return new ChatCompletionResponse(
        "chatcmpl-" + UUID.randomUUID(),
        "chat.completion",
        Instant.now().getEpochSecond(),
        response.model(),
        List.of(new ChatChoice(
            0,
            new ChatMessage("assistant", response.content()),
            response.finishReason()
        )),
        new TokenUsage(
            response.promptTokens(),
            response.completionTokens(),
            response.totalTokens()
        ),
        null
    );
  }
}
