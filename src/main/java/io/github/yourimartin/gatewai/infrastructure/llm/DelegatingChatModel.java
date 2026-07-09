package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.UnknownModelException;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * Provider-agnostic egress (Phase 7.2, generalized in Phase 8). The
 * {@code RoutingAdvisor} rewrites the prompt's model id per tier; this
 * delegating {@link ChatModel} resolves that id through the {@link ModelRegistry}
 * and dispatches to the matching {@link ProviderChatModels provider instance} —
 * any registered model on any configured provider, behind one advisor chain.
 *
 * <p>Marked {@link Primary} so Spring AI builds its {@code ChatClient} on this
 * model rather than on a single provider. There is <b>no fallback provider</b>:
 * a model id absent from the registry raises {@link UnknownModelException}
 * (mapped to an OpenAI-style 400), never a silent call to another vendor. A
 * client may also pin any registered model id directly; routing only rewrites it.
 *
 * <p>The advisor chain sets generic {@code ChatOptions} on the prompt; Anthropic
 * and OpenAI merge those portable options natively, but {@code OllamaChatModel}
 * hard-casts to {@code OllamaChatOptions}, so for Ollama instances the prompt is
 * rebuilt with provider-native options (model, temperature, top-p, num-predict).
 */
@Component
@Primary
@Profile("!mock")
class DelegatingChatModel implements ChatModel {

  private final ProviderChatModels providers;
  private final ModelRegistry modelRegistry;

  DelegatingChatModel(ProviderChatModels providers, ModelRegistry modelRegistry) {
    this.providers = providers;
    this.modelRegistry = modelRegistry;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    ProviderChatModels.ProviderInstance target = resolve(prompt);
    return target.chatModel().call(adapt(prompt, target));
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    ProviderChatModels.ProviderInstance target = resolve(prompt);
    return target.chatModel().stream(adapt(prompt, target));
  }

  @Override
  public ChatOptions getDefaultOptions() {
    // Neutral defaults — never leak one provider's options onto another's call.
    return ChatOptions.builder().build();
  }

  /** Resolves the prompt's model id to a configured provider instance, or fails. */
  private ProviderChatModels.ProviderInstance resolve(Prompt prompt) {
    ChatOptions options = prompt.getOptions();
    String modelId = options != null ? options.getModel() : null;
    if (modelId == null || modelId.isBlank()) {
      throw new UnknownModelException(
          "No model id on the request. Use one of the model ids declared in the gateway's registry.");
    }
    ModelDefinition definition = modelRegistry.findByModelId(modelId)
        .orElseThrow(() -> new UnknownModelException("Unknown model '" + modelId
            + "'. Use one of the model ids declared in the gateway's registry."));
    return providers.find(definition.provider())
        .orElseThrow(() -> new UnknownModelException("Model '" + modelId
            + "' maps to provider '" + definition.provider()
            + "', which is not configured on this gateway."));
  }

  private static Prompt adapt(Prompt prompt, ProviderChatModels.ProviderInstance target) {
    return target.type() == ProviderProperties.ProviderType.OLLAMA
        ? withOllamaOptions(prompt)
        : prompt;
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
