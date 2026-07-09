package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.management.PullModelStrategy;

/**
 * Verifies the egress factory builds exactly the provider instances the model
 * registry references, and fails fast — with actionable messages — on
 * misconfiguration. All builds are offline: no provider is called, and Ollama
 * instances use {@code pull-model-strategy=never}.
 */
class EgressProviderConfigurationTest {

  private final EgressProviderConfiguration configuration = new EgressProviderConfiguration();
  private final ToolCallingManager tools = ToolCallingManager.builder().build();
  private final ObservationRegistry observations = ObservationRegistry.NOOP;

  @Test
  void buildsOnlyReferencedProviderInstances() {
    ProviderProperties providers = providers(Map.of(
        "ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null),
        "anthropic", entry(ProviderProperties.ProviderType.ANTHROPIC, null, null)));

    ProviderChatModels models = configuration.create(
        providers, localOnlyRegistry(), tools, observations);

    assertThat(models.find("ollama")).isPresent();
    assertThat(models.find("ollama").orElseThrow().type())
        .isEqualTo(ProviderProperties.ProviderType.OLLAMA);
    // Declared but unreferenced: not built, and its missing API key is not an error.
    assertThat(models.find("anthropic")).isEmpty();
  }

  @Test
  void findsInstancesCaseInsensitively() {
    ProviderChatModels models = configuration.create(
        providers(Map.of("Ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null))),
        localOnlyRegistry(), tools, observations);

    assertThat(models.find("OLLAMA")).isPresent();
  }

  @Test
  void buildsAnthropicInstanceWhenKeyPresent() {
    ProviderProperties providers = providers(Map.of(
        "ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null),
        "anthropic", entry(ProviderProperties.ProviderType.ANTHROPIC, "sk-test", null)));
    ModelRegistry registry = registry(
        model("ollama", "qwen2.5:0.5b", ModelTier.LOCAL),
        model("ollama", "qwen2.5:1.5b", ModelTier.CLOUD_ENTRY),
        model("anthropic", "claude-opus-4-8", ModelTier.CLOUD_PREMIUM));

    ProviderChatModels models = configuration.create(providers, registry, tools, observations);

    assertThat(models.find("anthropic").orElseThrow().type())
        .isEqualTo(ProviderProperties.ProviderType.ANTHROPIC);
  }

  @Test
  void buildsOpenAiCompatibleInstanceWithoutApiKey() {
    ProviderProperties providers = providers(Map.of(
        "ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null),
        "vllm", entry(ProviderProperties.ProviderType.OPENAI_COMPATIBLE,
            null, "http://gpu-box:8000/v1")));
    ModelRegistry registry = registry(
        model("ollama", "qwen2.5:0.5b", ModelTier.LOCAL),
        model("ollama", "qwen2.5:1.5b", ModelTier.CLOUD_ENTRY),
        model("vllm", "llama-3.3-70b", ModelTier.CLOUD_PREMIUM));

    ProviderChatModels models = configuration.create(providers, registry, tools, observations);

    assertThat(models.find("vllm")).isPresent();
  }

  @Test
  void failsWhenRegistryReferencesUndeclaredProvider() {
    ModelRegistry registry = registry(
        model("ollama", "qwen2.5:0.5b", ModelTier.LOCAL),
        model("ollama", "qwen2.5:1.5b", ModelTier.CLOUD_ENTRY),
        model("anthropic", "claude-opus-4-8", ModelTier.CLOUD_PREMIUM));

    assertThatThrownBy(() -> configuration.create(
        providers(Map.of("ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null))),
        registry, tools, observations))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gatewai.providers.anthropic");
  }

  @Test
  void failsWhenAnthropicInstanceHasNoApiKey() {
    ProviderProperties providers = providers(Map.of(
        "ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null),
        "anthropic", entry(ProviderProperties.ProviderType.ANTHROPIC, " ", null)));
    ModelRegistry registry = registry(
        model("ollama", "qwen2.5:0.5b", ModelTier.LOCAL),
        model("ollama", "qwen2.5:1.5b", ModelTier.CLOUD_ENTRY),
        model("anthropic", "claude-opus-4-8", ModelTier.CLOUD_PREMIUM));

    assertThatThrownBy(() -> configuration.create(providers, registry, tools, observations))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gatewai.providers.anthropic.api-key");
  }

  @Test
  void failsWhenOpenAiCompatibleInstanceHasNoBaseUrl() {
    ProviderProperties providers = providers(Map.of(
        "ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null),
        "vllm", entry(ProviderProperties.ProviderType.OPENAI_COMPATIBLE, null, null)));
    ModelRegistry registry = registry(
        model("ollama", "qwen2.5:0.5b", ModelTier.LOCAL),
        model("ollama", "qwen2.5:1.5b", ModelTier.CLOUD_ENTRY),
        model("vllm", "llama-3.3-70b", ModelTier.CLOUD_PREMIUM));

    assertThatThrownBy(() -> configuration.create(providers, registry, tools, observations))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gatewai.providers.vllm.base-url");
  }

  @Test
  void failsWhenTierHasNoModel() {
    ModelRegistry registry = registry(
        model("ollama", "qwen2.5:0.5b", ModelTier.LOCAL),
        model("ollama", "qwen2.5:1.5b", ModelTier.CLOUD_ENTRY));

    assertThatThrownBy(() -> configuration.create(
        providers(Map.of("ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null))),
        registry, tools, observations))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CLOUD_PREMIUM");
  }

  @Test
  void failsOnDuplicateModelId() {
    ModelRegistry registry = registry(
        model("ollama", "qwen2.5:0.5b", ModelTier.LOCAL),
        model("ollama", "qwen2.5:0.5b", ModelTier.CLOUD_ENTRY),
        model("ollama", "qwen2.5:3b", ModelTier.CLOUD_PREMIUM));

    assertThatThrownBy(() -> configuration.create(
        providers(Map.of("ollama", entry(ProviderProperties.ProviderType.OLLAMA, null, null))),
        registry, tools, observations))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate model id");
  }

  @Test
  void failsWhenProviderHasNoType() {
    assertThatThrownBy(() -> configuration.create(
        providers(Map.of("ollama", entry(null, null, null))),
        localOnlyRegistry(), tools, observations))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gatewai.providers.ollama.type");
  }

  private static ProviderProperties providers(
      Map<String, ProviderProperties.ProviderEntry> entries) {
    ProviderProperties properties = new ProviderProperties();
    properties.setProviders(new LinkedHashMap<>(entries));
    return properties;
  }

  private static ProviderProperties.ProviderEntry entry(ProviderProperties.ProviderType type,
                                                        String apiKey, String baseUrl) {
    ProviderProperties.ProviderEntry entry = new ProviderProperties.ProviderEntry();
    entry.setType(type);
    entry.setApiKey(apiKey);
    entry.setBaseUrl(baseUrl);
    // Keep unit tests offline: never contact an Ollama server.
    entry.setPullModelStrategy(PullModelStrategy.NEVER);
    return entry;
  }

  /** A complete local-only registry: one Ollama model per routing tier. */
  private static ModelRegistry localOnlyRegistry() {
    return registry(
        model("ollama", "qwen2.5:0.5b", ModelTier.LOCAL),
        model("ollama", "qwen2.5:1.5b", ModelTier.CLOUD_ENTRY),
        model("ollama", "qwen2.5:3b", ModelTier.CLOUD_PREMIUM));
  }

  private static ModelRegistry registry(ModelRegistryProperties.ModelEntry... entries) {
    ModelRegistryProperties properties = new ModelRegistryProperties();
    Map<String, ModelRegistryProperties.ModelEntry> map = new LinkedHashMap<>();
    for (ModelRegistryProperties.ModelEntry entry : entries) {
      map.put(entry.getModelId() + "@" + entry.getTier(), entry);
    }
    properties.setRegistry(map);
    return new PropertiesModelRegistry(properties);
  }

  private static ModelRegistryProperties.ModelEntry model(String provider, String modelId,
                                                          ModelTier tier) {
    ModelRegistryProperties.ModelEntry entry = new ModelRegistryProperties.ModelEntry();
    entry.setProvider(provider);
    entry.setModelId(modelId);
    entry.setTier(tier);
    return entry;
  }
}
