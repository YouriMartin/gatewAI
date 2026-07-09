package io.github.yourimartin.gatewai.infrastructure.llm;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Builds the egress {@link ProviderChatModels} from configuration and fails fast
 * on inconsistencies (Phase 8). The model registry is the single source of
 * truth: only provider instances actually referenced by a registry entry are
 * built, so a deployment configures exactly the vendors it uses — 100% local,
 * 100% cloud, or any mix — and no API key is ever required for an unused one.
 *
 * <p>Replaces the Spring AI chat auto-configurations (excluded in
 * {@code application.properties}): building the models here allows N instances,
 * including several servers of the same type, with per-instance connections.
 * The Ollama embedding auto-configuration stays on — the semantic cache always
 * embeds locally.
 *
 * <p>Startup validation (all failures carry the property to fix):
 * <ul>
 *   <li>every routing tier has at least one registry entry (with no fallback
 *       provider, an empty tier would have nowhere to send its traffic);</li>
 *   <li>model ids are unique across the registry (dispatch resolves by id);</li>
 *   <li>every registry entry references a declared provider instance;</li>
 *   <li>referenced instances have the credentials their type requires.</li>
 * </ul>
 */
@Configuration
@Profile("!mock")
class EgressProviderConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(EgressProviderConfiguration.class);

  private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
  private static final Duration OPENAI_TIMEOUT = Duration.ofSeconds(60);
  private static final int OPENAI_MAX_RETRIES = 3;
  /** Anthropic requires max_tokens; used when the client sends none. */
  private static final int ANTHROPIC_DEFAULT_MAX_TOKENS = 4096;

  @Bean
  ProviderChatModels providerChatModels(ProviderProperties providerProperties,
                                        ModelRegistry modelRegistry,
                                        ObjectProvider<ToolCallingManager> toolCallingManager,
                                        ObjectProvider<ObservationRegistry> observationRegistry) {
    return create(providerProperties, modelRegistry,
        toolCallingManager.getIfAvailable(() -> ToolCallingManager.builder().build()),
        observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
  }

  ProviderChatModels create(ProviderProperties providerProperties,
                            ModelRegistry modelRegistry,
                            ToolCallingManager tools,
                            ObservationRegistry observations) {
    validateRegistry(modelRegistry);

    Map<String, ProviderProperties.ProviderEntry> declared = lowercaseKeys(providerProperties);
    Map<String, List<ModelDefinition>> referenced = modelsByProvider(modelRegistry);

    Map<String, ProviderChatModels.ProviderInstance> instances = new LinkedHashMap<>();
    referenced.forEach((name, models) -> {
      ProviderProperties.ProviderEntry entry = declared.get(name);
      if (entry == null) {
        throw new IllegalStateException(
            "Model registry entries " + keysOf(models) + " reference provider '" + name
                + "', but no such provider instance is declared. Add gatewai.providers." + name
                + ".type=(anthropic|openai|openai-compatible|ollama) — declared instances: "
                + declared.keySet());
      }
      if (entry.getType() == null) {
        throw new IllegalStateException("Provider '" + name + "' has no type. Set gatewai.providers."
            + name + ".type=(anthropic|openai|openai-compatible|ollama).");
      }
      instances.put(name, new ProviderChatModels.ProviderInstance(
          entry.getType(), build(name, entry, models, tools, observations)));
      LOG.info("Egress provider '{}' ({}) serves models {}", name, entry.getType(), keysOf(models));
    });

    declared.keySet().stream()
        .filter(name -> !instances.containsKey(name))
        .forEach(name -> LOG.info(
            "Egress provider '{}' is declared but referenced by no registry entry — not built", name));

    return new ProviderChatModels(instances);
  }

  private static void validateRegistry(ModelRegistry modelRegistry) {
    for (ModelTier tier : ModelTier.values()) {
      if (modelRegistry.findByTier(tier).isEmpty()) {
        throw new IllegalStateException("No model registered for routing tier " + tier
            + ". Add a gatewai.models.registry.<key> entry with tier="
            + tier.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".");
      }
    }
    Set<String> seen = new HashSet<>();
    for (ModelDefinition model : modelRegistry.allModels()) {
      if (!seen.add(model.modelId())) {
        throw new IllegalStateException("Duplicate model id '" + model.modelId()
            + "' in gatewai.models.registry — dispatch resolves by model id, so each id must be unique.");
      }
    }
  }

  private ChatModel build(String name,
                          ProviderProperties.ProviderEntry entry,
                          List<ModelDefinition> models,
                          ToolCallingManager tools,
                          ObservationRegistry observations) {
    return switch (entry.getType()) {
      case ANTHROPIC -> buildAnthropic(name, entry, models, tools, observations);
      case OPENAI, OPENAI_COMPATIBLE -> buildOpenAi(name, entry, models, tools, observations);
      case OLLAMA -> buildOllama(entry, models, tools, observations);
    };
  }

  private ChatModel buildAnthropic(String name,
                                   ProviderProperties.ProviderEntry entry,
                                   List<ModelDefinition> models,
                                   ToolCallingManager tools,
                                   ObservationRegistry observations) {
    requireApiKey(name, entry, "an Anthropic");
    AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
        .model(models.getFirst().modelId())
        .maxTokens(ANTHROPIC_DEFAULT_MAX_TOKENS)
        .apiKey(entry.getApiKey());
    if (hasText(entry.getBaseUrl())) {
      options.baseUrl(entry.getBaseUrl());
    }
    return AnthropicChatModel.builder()
        .options(options.build())
        .toolCallingManager(tools)
        .observationRegistry(observations)
        .build();
  }

  private ChatModel buildOpenAi(String name,
                                ProviderProperties.ProviderEntry entry,
                                List<ModelDefinition> models,
                                ToolCallingManager tools,
                                ObservationRegistry observations) {
    if (entry.getType() == ProviderProperties.ProviderType.OPENAI) {
      requireApiKey(name, entry, "an OpenAI");
    } else if (!hasText(entry.getBaseUrl())) {
      throw new IllegalStateException("Provider '" + name + "' is openai-compatible but has no"
          + " endpoint. Set gatewai.providers." + name + ".base-url to the server root"
          + " (e.g. http://vllm-host:8000/v1).");
    }
    // Empty key => the SDK's no-auth mode, common for self-hosted OpenAI-compatible servers.
    String apiKey = entry.getApiKey() == null ? "" : entry.getApiKey();
    String baseUrl = hasText(entry.getBaseUrl()) ? entry.getBaseUrl() : null;
    return OpenAiChatModel.builder()
        .openAiClient(OpenAiSetup.setupSyncClient(baseUrl, apiKey, null, null, null, null,
            false, false, null, OPENAI_TIMEOUT, OPENAI_MAX_RETRIES, null, Map.of(),
            observations, null, List.of()))
        .openAiClientAsync(OpenAiSetup.setupAsyncClient(baseUrl, apiKey, null, null, null, null,
            false, false, null, OPENAI_TIMEOUT, OPENAI_MAX_RETRIES, null, Map.of(),
            observations, null, List.of()))
        .options(OpenAiChatOptions.builder().model(models.getFirst().modelId()).build())
        .toolCallingManager(tools)
        .observationRegistry(observations)
        .build();
  }

  private ChatModel buildOllama(ProviderProperties.ProviderEntry entry,
                                List<ModelDefinition> models,
                                ToolCallingManager tools,
                                ObservationRegistry observations) {
    String baseUrl = hasText(entry.getBaseUrl()) ? entry.getBaseUrl() : DEFAULT_OLLAMA_BASE_URL;
    List<String> modelIds = models.stream().map(ModelDefinition::modelId).toList();
    return OllamaChatModel.builder()
        .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
        .options(OllamaChatOptions.builder().model(modelIds.getFirst()).build())
        // Pulls this instance's registry models per the configured strategy.
        .modelManagementOptions(ModelManagementOptions.builder()
            .pullModelStrategy(entry.getPullModelStrategy())
            .additionalModels(modelIds.subList(1, modelIds.size()))
            .build())
        .toolCallingManager(tools)
        .observationRegistry(observations)
        .build();
  }

  private static void requireApiKey(String name, ProviderProperties.ProviderEntry entry,
                                    String vendor) {
    if (!hasText(entry.getApiKey())) {
      throw new IllegalStateException("Provider '" + name + "' is " + vendor
          + " instance but has no API key. Set gatewai.providers." + name
          + ".api-key (usually via an environment variable).");
    }
  }

  private static Map<String, ProviderProperties.ProviderEntry> lowercaseKeys(
      ProviderProperties properties) {
    return properties.getProviders().entrySet().stream().collect(Collectors.toMap(
        e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue,
        (a, b) -> a, LinkedHashMap::new));
  }

  private static Map<String, List<ModelDefinition>> modelsByProvider(ModelRegistry registry) {
    return registry.allModels().stream().collect(Collectors.groupingBy(
        model -> model.provider().toLowerCase(Locale.ROOT),
        LinkedHashMap::new, Collectors.toList()));
  }

  private static List<String> keysOf(List<ModelDefinition> models) {
    return models.stream().map(ModelDefinition::key).toList();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
