package com.example.gatewai.infrastructure.llm;

import java.util.List;
import java.util.Objects;

import com.example.gatewai.domain.model.LlmMessage;
import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.port.out.LlmClient;

import org.springframework.ai.chat.client.ChatClient;
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

  SpringAiLlmClient(ChatClient.Builder chatClientBuilder) {
    this.chatClient = chatClientBuilder.build();
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

    return new LlmResponse(
        model, content, finishReason,
        promptTokens, completionTokens, totalTokens);
  }
}
