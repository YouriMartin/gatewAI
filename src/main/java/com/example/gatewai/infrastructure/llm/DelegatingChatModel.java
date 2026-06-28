package com.example.gatewai.infrastructure.llm;

import com.example.gatewai.domain.model.ModelDefinition;
import com.example.gatewai.domain.port.out.ModelRegistry;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * Provider-aware egress (Phase 7.2). The {@code RoutingAdvisor} rewrites the
 * prompt's model id per tier; this delegating {@link ChatModel} reads that id,
 * resolves its provider through the {@link ModelRegistry}, and dispatches to the
 * matching real provider — so the {@code LOCAL} tier reaches Ollama while the
 * cloud tiers reach Anthropic, all behind the same advisor chain.
 *
 * <p>Marked {@link Primary} so Spring AI builds its {@code ChatClient} on this
 * model rather than on a single provider. Unknown/missing model ids fall back to
 * Anthropic.
 *
 * <p>The advisor chain sets generic {@code ChatOptions} on the prompt; Anthropic
 * accepts those, but {@code OllamaChatModel} hard-casts to {@code
 * OllamaChatOptions}, so for Ollama we rebuild the prompt with provider-native
 * options (model, temperature, top-p, num-predict).
 */
@Component
@Primary
class DelegatingChatModel implements ChatModel {

  private static final String PROVIDER_OLLAMA = "ollama";

  private final ChatModel anthropic;
  private final ChatModel ollama;
  private final ModelRegistry modelRegistry;

  DelegatingChatModel(@Qualifier("anthropicChatModel") ChatModel anthropic,
                      @Qualifier("ollamaChatModel") ChatModel ollama,
                      ModelRegistry modelRegistry) {
    this.anthropic = anthropic;
    this.ollama = ollama;
    this.modelRegistry = modelRegistry;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    return routesToOllama(prompt)
        ? ollama.call(withOllamaOptions(prompt))
        : anthropic.call(prompt);
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return routesToOllama(prompt)
        ? ollama.stream(withOllamaOptions(prompt))
        : anthropic.stream(prompt);
  }

  @Override
  public ChatOptions getDefaultOptions() {
    // Neutral defaults — never leak one provider's options onto another's call.
    return ChatOptions.builder().build();
  }

  private boolean routesToOllama(Prompt prompt) {
    ChatOptions options = prompt.getOptions();
    String modelId = options != null ? options.getModel() : null;
    if (modelId == null) {
      return false;
    }
    return PROVIDER_OLLAMA.equalsIgnoreCase(modelRegistry.findByModelId(modelId)
        .map(ModelDefinition::provider)
        .orElse(null));
  }

  /** Rebuilds the prompt with {@link OllamaChatOptions} (Ollama hard-casts). */
  private static Prompt withOllamaOptions(Prompt prompt) {
    ChatOptions in = prompt.getOptions();
    OllamaChatOptions.Builder out = OllamaChatOptions.builder();
    if (in != null) {
      if (in.getModel() != null) {
        out.model(in.getModel());
      }
      if (in.getTemperature() != null) {
        out.temperature(in.getTemperature());
      }
      if (in.getTopP() != null) {
        out.topP(in.getTopP());
      }
      if (in.getMaxTokens() != null) {
        out.numPredict(in.getMaxTokens());
      }
    }
    return new Prompt(prompt.getInstructions(), out.build());
  }
}
