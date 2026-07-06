package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * Deterministic echo egress for the {@code mock} profile (Phase 7.4). It replaces
 * the real provider call with a canned reply, so the whole app can run and serve
 * {@code /v1/chat/completions} with <b>no API key and zero cost</b> — useful for
 * demos, dashboard work, and plumbing tests.
 *
 * <p>It sits at the {@link ChatModel} level (not {@code LlmClient}), so the advisor
 * chain still runs: the semantic cache and the router execute around it, and the
 * response carries the routed model id and token counts, so green accounting,
 * persistence and reporting all behave realistically. Still needs the local
 * embedding + Postgres for the cache; it just never calls a paid provider.
 */
@Component
@Primary
@Profile("mock")
class MockEchoChatModel implements ChatModel {

  @Override
  public ChatResponse call(Prompt prompt) {
    String userText = userText(prompt);
    String reply = userText.isBlank()
        ? "[mock] (empty prompt)"
        : "[mock] echo: " + userText;

    Generation generation = new Generation(
        new AssistantMessage(reply),
        ChatGenerationMetadata.builder().finishReason("stop").build());

    ChatResponseMetadata metadata = ChatResponseMetadata.builder()
        .model(modelId(prompt))
        .usage(new DefaultUsage(tokens(userText), tokens(reply)))
        .build();

    return new ChatResponse(List.of(generation), metadata);
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.just(call(prompt));
  }

  @Override
  public ChatOptions getDefaultOptions() {
    return ChatOptions.builder().build();
  }

  private static String userText(Prompt prompt) {
    UserMessage user = prompt.getUserMessage();
    return user != null && user.getText() != null ? user.getText() : "";
  }

  private static String modelId(Prompt prompt) {
    ChatOptions options = prompt.getOptions();
    String model = options != null ? options.getModel() : null;
    return model != null ? model : "mock";
  }

  /** Crude whitespace token count — enough to make accounting figures non-zero. */
  private static int tokens(String text) {
    if (text == null || text.isBlank()) {
      return 0;
    }
    return text.trim().split("\\s+").length;
  }
}
