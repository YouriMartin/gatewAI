package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The quickstart's egress presets (v4 C.3) as the real config-data loading
 * resolves them: {@code application.properties}, the profile file a preset
 * activates, and environment-style overrides. Nothing but the environment is
 * started — the configuration class below is empty.
 */
class QuickstartPresetsTest {

  private static final List<String> ENTRIES = List.of("local-small", "local-medium", "local-large");

  @Configuration(proxyBeanMethods = false)
  static class NoBeans {
  }

  @Test
  void defaultModelIdsSurviveTheColonInsideTheirPlaceholderDefault() {
    Map<String, ModelRegistryProperties.ModelEntry> registry = registry(Map.of());

    assertEquals(List.of("qwen2.5:0.5b", "qwen2.5:1.5b", "qwen2.5:3b"), modelIds(registry));
    ENTRIES.forEach(name -> assertEquals("ollama", registry.get(name).getProvider()));
  }

  @Test
  void eachDefaultModelIdIsOverriddenByItsOwnVariable() {
    Map<String, ModelRegistryProperties.ModelEntry> registry = registry(Map.of(
        "GATEWAI_MODEL_SMALL", "llama3.2:1b",
        "GATEWAI_MODEL_LARGE", "mistral:7b"));

    assertEquals(List.of("llama3.2:1b", "qwen2.5:1.5b", "mistral:7b"), modelIds(registry));
  }

  @Test
  void openAiCompatiblePresetMovesTheThreeEntriesToOneEndpointAndKeepsTheirTiers() {
    Map<String, String> env = Map.of(
        "OPENAI_COMPATIBLE_BASE_URL", "http://vllm:8000/v1",
        "OPENAI_COMPATIBLE_API_KEY", "sk-test",
        "GATEWAI_MODEL_SMALL", "small",
        "GATEWAI_MODEL_MEDIUM", "medium",
        "GATEWAI_MODEL_LARGE", "large");
    try (ConfigurableApplicationContext context = start(env, "openai-compatible")) {
      Binder binder = Binder.get(context.getEnvironment());
      Map<String, ModelRegistryProperties.ModelEntry> registry =
          binder.bind("gatewai.models", ModelRegistryProperties.class).get().getRegistry();
      ProviderProperties.ProviderEntry provider = binder.bind("gatewai", ProviderProperties.class)
          .get().getProviders().get("openai-compatible");

      assertEquals(List.of("small", "medium", "large"), modelIds(registry));
      ENTRIES.forEach(name -> assertEquals("openai-compatible", registry.get(name).getProvider()));
      assertEquals(List.of(ModelTier.LOCAL, ModelTier.CLOUD_ENTRY, ModelTier.CLOUD_PREMIUM),
          ENTRIES.stream().map(name -> registry.get(name).getTier()).toList());
      assertEquals(ProviderProperties.ProviderType.OPENAI_COMPATIBLE, provider.getType());
      assertEquals("http://vllm:8000/v1", provider.getBaseUrl());
      assertEquals("sk-test", provider.getApiKey());
    }
  }

  @Test
  void openAiCompatiblePresetRefusesToStartWithoutAModelId() {
    Map<String, String> env = Map.of(
        "OPENAI_COMPATIBLE_BASE_URL", "http://vllm:8000/v1",
        "GATEWAI_MODEL_SMALL", "small",
        "GATEWAI_MODEL_MEDIUM", "medium");
    try (ConfigurableApplicationContext context = start(env, "openai-compatible")) {
      Binder binder = Binder.get(context.getEnvironment());
      ModelRegistryProperties registry =
          binder.bind("gatewai.models", ModelRegistryProperties.class).get();
      ProviderProperties providers = binder.bind("gatewai", ProviderProperties.class).get();

      // Binding does not fail: the unset variable stays as text. Startup validation does.
      assertEquals("${GATEWAI_MODEL_LARGE}", registry.getRegistry().get("local-large").getModelId());
      IllegalStateException failure = assertThrows(IllegalStateException.class,
          () -> new EgressProviderConfiguration().create(providers,
              new PropertiesModelRegistry(registry), ToolCallingManager.builder().build(),
              ObservationRegistry.NOOP));
      assertTrue(failure.getMessage().contains("gatewai.models.registry.local-large.model-id"));
    }
  }

  private static Map<String, ModelRegistryProperties.ModelEntry> registry(Map<String, String> env) {
    try (ConfigurableApplicationContext context = start(env)) {
      return Binder.get(context.getEnvironment())
          .bind("gatewai.models", ModelRegistryProperties.class).get().getRegistry();
    }
  }

  private static ConfigurableApplicationContext start(Map<String, String> env, String... profiles) {
    Map<String, Object> properties = new HashMap<>(env);
    properties.put("spring.docker.compose.enabled", "false");
    properties.put("spring.main.banner-mode", "off");
    return new SpringApplicationBuilder(NoBeans.class)
        .web(WebApplicationType.NONE)
        .profiles(profiles)
        .properties(properties)
        .logStartupInfo(false)
        .run();
  }

  private static List<String> modelIds(Map<String, ModelRegistryProperties.ModelEntry> registry) {
    return ENTRIES.stream().map(name -> registry.get(name).getModelId()).toList();
  }
}
