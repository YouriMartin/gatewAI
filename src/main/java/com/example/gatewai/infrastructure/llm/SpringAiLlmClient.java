package com.example.gatewai.infrastructure.llm;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.example.gatewai.domain.model.LlmMessage;
import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.model.LlmStreamChunk;
import com.example.gatewai.domain.port.out.LlmClient;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

@Component
class SpringAiLlmClient implements LlmClient {

  private final ChatClient chatClient;

  SpringAiLlmClient(ChatClient.Builder chatClientBuilder,
                     List<Advisor> advisors) {
    this.chatClient = chatClientBuilder
        .defaultAdvisors(advisors)
        .build();
  }

  @Override
  public LlmResponse call(LlmRequest request) {
    List<Message> springMessages = request.messages().stream()
        .map(SpringAiLlmClient::toSpringMessage)
        .toList();

    var optionsBuilder = ChatOptions.builder().model(request.model());
    if (request.temperature() != null) {
      optionsBuilder.temperature(request.temperature());
    }
    if (request.maxTokens() != null) {
      optionsBuilder.maxTokens(request.maxTokens());
    }

    ChatResponse chatResponse = Objects.requireNonNull(
        chatClient.prompt()
            .messages(springMessages)
            .options(optionsBuilder)
            .call()
            .chatResponse(),
        "ChatResponse must not be null");

    return toLlmResponse(chatResponse);
  }

  @Override
  public void stream(LlmRequest request, Consumer<LlmStreamChunk> onChunk) {
    List<Message> springMessages = request.messages().stream()
        .map(SpringAiLlmClient::toSpringMessage)
        .toList();

    var optionsBuilder = ChatOptions.builder().model(request.model());
    if (request.temperature() != null) {
      optionsBuilder.temperature(request.temperature());
    }
    if (request.maxTokens() != null) {
      optionsBuilder.maxTokens(request.maxTokens());
    }

    // toStream() drains the reactive pipeline (incl. the cache/routing advisors)
    // on the calling thread, so Reactor stays confined to this adapter.
    chatClient.prompt()
        .messages(springMessages)
        .options(optionsBuilder)
        .stream()
        .chatResponse()
        .toStream()
        .forEach(chatResponse -> onChunk.accept(toChunk(chatResponse)));
  }

  private static LlmStreamChunk toChunk(ChatResponse chatResponse) {
    var result = chatResponse.getResult();
    String delta = result != null && result.getOutput() != null
        && result.getOutput().getText() != null
        ? result.getOutput().getText() : "";

    var responseMeta = chatResponse.getMetadata();
    String model = responseMeta != null ? responseMeta.getModel() : null;
    String finishReason = result != null && result.getMetadata() != null
        ? result.getMetadata().getFinishReason() : null;
    boolean last = finishReason != null && !finishReason.isBlank();

    int promptTokens = 0;
    int completionTokens = 0;
    int totalTokens = 0;
    if (responseMeta != null && responseMeta.getUsage() != null) {
      Usage usage = responseMeta.getUsage();
      promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
      completionTokens =
          usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
      totalTokens = usage.getTotalTokens() != null
          ? usage.getTotalTokens() : promptTokens + completionTokens;
    }

    boolean cacheHit = responseMeta != null
        && Boolean.TRUE.equals(responseMeta.get(LlmResponse.CACHE_HIT_METADATA_KEY));

    return new LlmStreamChunk(model, delta, finishReason, cacheHit,
        promptTokens, completionTokens, totalTokens, last);
  }

  private static Message toSpringMessage(LlmMessage msg) {
    return switch (msg.role()) {
      case "system" -> new SystemMessage(msg.content());
      case "assistant" -> new AssistantMessage(msg.content());
      default -> new UserMessage(msg.content());
    };
  }

  private static LlmResponse toLlmResponse(ChatResponse chatResponse) {
    var result = Objects.requireNonNull(chatResponse.getResult());
    String content = Objects.requireNonNull(result.getOutput()).getText();
    String model = Objects.requireNonNull(chatResponse.getMetadata()).getModel();

    var resultMeta = result.getMetadata();
    String finishReason = resultMeta != null ? resultMeta.getFinishReason() : null;

    Usage usage = Objects.requireNonNull(chatResponse.getMetadata().getUsage());
    int promptTokens = usage.getPromptTokens() != null
        ? usage.getPromptTokens() : 0;
    int completionTokens = usage.getCompletionTokens() != null
        ? usage.getCompletionTokens() : 0;
    int totalTokens = usage.getTotalTokens() != null
        ? usage.getTotalTokens() : 0;

    Boolean cacheHitFlag =
        chatResponse.getMetadata().get(LlmResponse.CACHE_HIT_METADATA_KEY);
    boolean cacheHit = Boolean.TRUE.equals(cacheHitFlag);

    return new LlmResponse(
        model, content, finishReason,
        promptTokens, completionTokens, totalTokens, cacheHit);
  }
}
