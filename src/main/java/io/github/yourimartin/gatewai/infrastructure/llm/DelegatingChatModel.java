package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.UnknownModelException;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
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
 * <p>The advisor chain sets portable options on the prompt, but every Spring AI
 * 2.0 provider model expects its <b>own</b> options type: {@code OpenAiChatModel}
 * and {@code OllamaChatModel} hard-cast to it, and {@code AnthropicChatModel}
 * silently replaces anything else with empty options (no model, no max tokens).
 * So the prompt is always re-based on the target instance's own options (which
 * hold its model, credentials and defaults), with the request's options merged
 * over them — and the {@link GatewaiChatOptions#getSeed() seed} set where the
 * provider takes one (v4 B.1). Until B.1 only Ollama was re-based, which left
 * every routed call to an {@code openai}/{@code openai-compatible} instance
 * failing with a {@code ClassCastException}.
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

  /**
   * Neutral defaults — never leak one provider's options onto another's call. Of
   * the gateway's type, so the {@code ChatClient}'s merge keeps the request's seed.
   */
  @Override
  public ChatOptions getOptions() {
    return GatewaiChatOptions.builder().build();
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

  /** The prompt with the target's own options type, the request's options merged in. */
  private static Prompt adapt(Prompt prompt, ProviderChatModels.ProviderInstance target) {
    ChatOptions defaults = target.chatModel().getOptions();
    ChatOptions.Builder<?> options = defaults == null
        ? ChatOptions.builder() : defaults.mutate();
    ChatOptions requested = prompt.getOptions();
    if (requested != null) {
      options.combineWith(requested.mutate());
    }
    Integer seed = intSeed(GatewaiChatOptions.seedOf(requested));
    if (options instanceof OpenAiChatOptions.Builder openAi) {
      openAi.seed(seed);
      if (target.type() == ProviderProperties.ProviderType.OPENAI
          && requested != null && requested.getMaxTokens() != null) {
        // OpenAI deprecated max_tokens and its reasoning models reject it; servers
        // that merely speak the format (openai-compatible) still expect max_tokens.
        openAi.maxTokens(null).maxCompletionTokens(requested.getMaxTokens());
      }
    } else if (options instanceof OllamaChatOptions.Builder ollama) {
      ollama.seed(seed);
    }
    // Anthropic has no seed: it is ignored there, as documented.
    return new Prompt(prompt.getInstructions(), options.build());
  }

  /** Both providers that take a seed take a 32-bit one; a larger seed is not sent. */
  private static Integer intSeed(Long seed) {
    return seed == null || seed != seed.intValue() ? null : seed.intValue();
  }
}
