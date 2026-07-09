package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.UnknownModelException;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import reactor.core.publisher.Flux;

/** Verifies the delegating egress dispatches by provider instance, with no fallback. */
class DelegatingChatModelTest {

  private final ChatModel anthropic = mock(ChatModel.class);
  private final ChatModel ollama = mock(ChatModel.class);
  private final ChatModel openAi = mock(ChatModel.class);
  private final ModelRegistry registry = mock(ModelRegistry.class);
  private final DelegatingChatModel delegating = new DelegatingChatModel(
      new ProviderChatModels(Map.of(
          "anthropic", new ProviderChatModels.ProviderInstance(
              ProviderProperties.ProviderType.ANTHROPIC, anthropic),
          "my-ollama", new ProviderChatModels.ProviderInstance(
              ProviderProperties.ProviderType.OLLAMA, ollama),
          "openai", new ProviderChatModels.ProviderInstance(
              ProviderProperties.ProviderType.OPENAI, openAi))),
      registry);

  private static Prompt promptFor(String modelId) {
    return new Prompt("hello", ChatOptions.builder().model(modelId).build());
  }

  private void register(String modelId, String provider) {
    when(registry.findByModelId(modelId)).thenReturn(Optional.of(
        new ModelDefinition("k", provider, modelId, 0.0, 0.0, ModelTier.CLOUD_PREMIUM)));
  }

  @Test
  void routesOpenAiProviderPassingOptionsThrough() {
    register("gpt-4o", "openai");
    when(openAi.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));
    Prompt prompt = promptFor("gpt-4o");

    delegating.call(prompt);

    verify(openAi).call(prompt);
    verify(anthropic, never()).call(any(Prompt.class));
    verify(ollama, never()).call(any(Prompt.class));
  }

  @Test
  void resolvesProviderNamesCaseInsensitively() {
    register("gpt-4o", "OpenAI");
    when(openAi.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));

    delegating.call(promptFor("gpt-4o"));

    verify(openAi).call(any(Prompt.class));
  }

  @Test
  void routesOllamaInstanceWithNativeOptions() {
    register("qwen2.5:0.5b", "my-ollama");
    when(ollama.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));

    delegating.call(promptFor("qwen2.5:0.5b"));

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(ollama).call(captor.capture());
    // Ollama hard-casts its options, so the prompt must be rebuilt as OllamaChatOptions.
    assertThat(captor.getValue().getOptions()).isInstanceOf(OllamaChatOptions.class);
    verify(openAi, never()).call(any(Prompt.class));
  }

  @Test
  void routesAnthropicProviderPassedThrough() {
    register("claude-opus-4-8", "anthropic");
    when(anthropic.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));
    Prompt prompt = promptFor("claude-opus-4-8");

    delegating.call(prompt);

    verify(anthropic).call(prompt);
    verify(openAi, never()).call(any(Prompt.class));
  }

  @Test
  void unknownModelIdIsRejectedWithNoFallback() {
    when(registry.findByModelId("mystery")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegating.call(promptFor("mystery")))
        .isInstanceOf(UnknownModelException.class)
        .hasMessageContaining("mystery");

    verify(anthropic, never()).call(any(Prompt.class));
    verify(openAi, never()).call(any(Prompt.class));
    verify(ollama, never()).call(any(Prompt.class));
  }

  @Test
  void missingModelIdIsRejected() {
    assertThatThrownBy(() -> delegating.call(new Prompt("hello")))
        .isInstanceOf(UnknownModelException.class);
  }

  @Test
  void unconfiguredProviderIsRejected() {
    register("mistral-large", "mistral");

    assertThatThrownBy(() -> delegating.call(promptFor("mistral-large")))
        .isInstanceOf(UnknownModelException.class)
        .hasMessageContaining("mistral");
  }

  @Test
  void streamRoutesToOpenAiProvider() {
    register("gpt-4o", "openai");
    when(openAi.stream(any(Prompt.class))).thenReturn(Flux.empty());

    delegating.stream(promptFor("gpt-4o")).blockLast();

    verify(openAi).stream(any(Prompt.class));
    verify(anthropic, never()).stream(any(Prompt.class));
  }
}
