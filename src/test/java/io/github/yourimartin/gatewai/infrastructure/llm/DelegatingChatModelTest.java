package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.carbon.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.UnknownModelException;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

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

  /** Each provider model exposes its own options type, as the real ones do. */
  @BeforeEach
  void nativeDefaults() {
    when(anthropic.getOptions()).thenReturn(AnthropicChatOptions.builder()
        .model("claude-default").maxTokens(4096).build());
    when(ollama.getOptions()).thenReturn(OllamaChatOptions.builder().model("qwen-default").build());
    when(openAi.getOptions()).thenReturn(OpenAiChatOptions.builder().model("gpt-default").build());
  }

  private static Prompt promptFor(String modelId) {
    return new Prompt("hello", ChatOptions.builder().model(modelId).build());
  }

  private void register(String modelId, String provider) {
    when(registry.findByModelId(modelId)).thenReturn(Optional.of(
        new ModelDefinition("k", provider, modelId, 0.0, EnergyProfile.NOT_ACCOUNTED, ModelTier.CLOUD_PREMIUM)));
  }

  @Test
  void routesOpenAiProviderWithNativeOptions() {
    register("gpt-4o", "openai");
    when(openAi.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));

    delegating.call(new Prompt("hello", GatewaiChatOptions.builder()
        .model("gpt-4o").temperature(0.4).presencePenalty(0.1).seed(5L).build()));

    // OpenAiChatModel hard-casts: anything but its own options type is a ClassCastException.
    OpenAiChatOptions options = (OpenAiChatOptions) sentTo(openAi).getOptions();
    assertThat(options.getModel()).isEqualTo("gpt-4o");
    assertThat(options.getTemperature()).isEqualTo(0.4);
    assertThat(options.getPresencePenalty()).isEqualTo(0.1);
    assertThat(options.getSeed()).isEqualTo(5);
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

    delegating.call(new Prompt("hello", GatewaiChatOptions.builder()
        .model("qwen2.5:0.5b").maxTokens(64).stopSequences(List.of("END")).seed(5L).build()));

    // Ollama hard-casts its options, so the prompt must be rebuilt as OllamaChatOptions.
    OllamaChatOptions options = (OllamaChatOptions) sentTo(ollama).getOptions();
    assertThat(options.getModel()).isEqualTo("qwen2.5:0.5b");
    assertThat(options.getNumPredict()).isEqualTo(64);
    assertThat(options.getStop()).containsExactly("END");
    assertThat(options.getSeed()).isEqualTo(5);
    verify(openAi, never()).call(any(Prompt.class));
  }

  @Test
  void routesAnthropicProviderWithNativeOptionsAndItsDefaults() {
    register("claude-opus-4-8", "anthropic");
    when(anthropic.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));

    delegating.call(promptFor("claude-opus-4-8"));

    // AnthropicChatModel replaces foreign options with empty ones: no model, no max tokens.
    AnthropicChatOptions options = (AnthropicChatOptions) sentTo(anthropic).getOptions();
    assertThat(options.getModel()).isEqualTo("claude-opus-4-8");
    assertThat(options.getMaxTokens()).isEqualTo(4096);
    verify(openAi, never()).call(any(Prompt.class));
  }

  @Test
  void exposesOptionsThatKeepTheSeedThroughTheChatClientMerge() {
    ChatOptions merged = delegating.getOptions().mutate()
        .combineWith(GatewaiChatOptions.builder().model("m").seed(3L))
        .build();

    assertThat(GatewaiChatOptions.seedOf(merged)).isEqualTo(3L);
  }

  private static Prompt sentTo(ChatModel model) {
    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(captor.capture());
    return captor.getValue();
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
