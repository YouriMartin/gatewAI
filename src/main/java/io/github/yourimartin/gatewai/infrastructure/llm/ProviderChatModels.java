package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.ai.chat.model.ChatModel;

/**
 * The set of egress {@link ChatModel}s built from {@code gatewai.providers.*},
 * keyed by provider-instance name (lowercased). Built once at startup by
 * {@link EgressProviderConfiguration}; {@link DelegatingChatModel} looks up the
 * target per request. Holds only the instances referenced by the model registry.
 */
final class ProviderChatModels {

  /** A configured provider instance: its wire type and the ready-to-call model. */
  record ProviderInstance(ProviderProperties.ProviderType type, ChatModel chatModel) {}

  private final Map<String, ProviderInstance> instances;

  ProviderChatModels(Map<String, ProviderInstance> instances) {
    this.instances = Map.copyOf(instances);
  }

  Optional<ProviderInstance> find(String providerName) {
    if (providerName == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(instances.get(providerName.toLowerCase(Locale.ROOT)));
  }
}
