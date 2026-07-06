package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
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
 * and OpenAI merge those portable options natively, but {@code OllamaChatModel}
 * hard-casts to {@code OllamaChatOptions}, so for Ollama we rebuild the prompt
 * with provider-native options (model, temperature, top-p, num-predict).
 *
 * <p>OpenAI is an opt-in egress: no default tier points at it, so the OpenAI bean
 * (built in no-auth mode when {@code OPENAI_API_KEY} is empty) sits unused until
 * the model registry routes a tier to a {@code provider=openai} model.
 */
@Component
@Primary
@Profile("!mock")
class DelegatingChatModel implements ChatModel {

  private static final String PROVIDER_OLLAMA = "ollama";
  private static final String PROVIDER_OPENAI = "openai";

  private final ChatModel anthropic;
  private final ChatModel ollama;
  private final ChatModel openAi;
  private final ModelRegistry modelRegistry;

  DelegatingChatModel(@Qualifier("anthropicChatModel") ChatModel anthropic,
                      @Qualifier("ollamaChatModel") ChatModel ollama,
                      @Qualifier("openAiChatModel") ChatModel openAi,
                      ModelRegistry modelRegistry) {
    this.anthropic = anthropic;
    this.ollama = ollama;
    this.openAi = openAi;
    this.modelRegistry = modelRegistry;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    return switch (providerFor(prompt)) {
      case PROVIDER_OLLAMA -> ollama.call(withOllamaOptions(prompt));
      case PROVIDER_OPENAI -> openAi.call(prompt);
      default -> anthropic.call(prompt);
    };
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return switch (providerFor(prompt)) {
      case PROVIDER_OLLAMA -> ollama.stream(withOllamaOptions(prompt));
      case PROVIDER_OPENAI -> openAi.stream(prompt);
      default -> anthropic.stream(prompt);
    };
  }

  @Override
  public ChatOptions getDefaultOptions() {
    // Neutral defaults — never leak one provider's options onto another's call.
    return ChatOptions.builder().build();
  }

  /** Resolves the target provider (lowercased) for the prompt's model id. */
  private String providerFor(Prompt prompt) {
    ChatOptions options = prompt.getOptions();
    String modelId = options != null ? options.getModel() : null;
    if (modelId == null) {
      return "";
    }
    return modelRegistry.findByModelId(modelId)
        .map(ModelDefinition::provider)
        .map(provider -> provider.toLowerCase(java.util.Locale.ROOT))
        .orElse("");
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
