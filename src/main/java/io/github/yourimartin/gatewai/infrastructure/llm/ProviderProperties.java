package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Egress provider instances ({@code gatewai.providers.<name>}). Each entry is an
 * independent connection the gateway can dispatch to; model-registry entries
 * reference one by name via their {@code provider} field. Any mix is valid —
 * several Ollama servers, an OpenAI-compatible vLLM box, Anthropic, OpenAI — and
 * only the instances actually referenced by the registry are built at startup.
 */
@ConfigurationProperties(prefix = "gatewai")
class ProviderProperties {

  private Map<String, ProviderEntry> providers = new LinkedHashMap<>();

  Map<String, ProviderEntry> getProviders() {
    return providers;
  }

  void setProviders(Map<String, ProviderEntry> providers) {
    this.providers = providers;
  }

  /** Wire protocol / SDK used to reach a provider instance. */
  enum ProviderType {
    /** Anthropic API ({@code api-key} required, {@code base-url} optional). */
    ANTHROPIC,
    /** OpenAI API ({@code api-key} required, {@code base-url} optional). */
    OPENAI,
    /**
     * Any server speaking the OpenAI wire format — vLLM, LM Studio, llama.cpp,
     * OpenRouter… ({@code base-url} required, {@code api-key} optional).
     */
    OPENAI_COMPATIBLE,
    /** An Ollama server ({@code base-url} optional, defaults to localhost). */
    OLLAMA
  }

  static class ProviderEntry {

    private ProviderType type;
    private String apiKey;
    private String baseUrl;
    /** Ollama only: whether to pull the registry's models at startup. */
    private PullModelStrategy pullModelStrategy = PullModelStrategy.WHEN_MISSING;

    ProviderType getType() {
      return type;
    }

    void setType(ProviderType type) {
      this.type = type;
    }

    String getApiKey() {
      return apiKey;
    }

    void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    String getBaseUrl() {
      return baseUrl;
    }

    void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    PullModelStrategy getPullModelStrategy() {
      return pullModelStrategy;
    }

    void setPullModelStrategy(PullModelStrategy pullModelStrategy) {
      this.pullModelStrategy = pullModelStrategy;
    }
  }
}
