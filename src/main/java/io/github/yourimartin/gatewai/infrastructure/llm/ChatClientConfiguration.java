package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.ModelTier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RegisterReflectionForBinding(ClassificationResult.class)
class ChatClientConfiguration {

  // Multi-provider egress is handled by DelegatingChatModel, not by per-tier
  // ChatClients: routing rewrites the model id and the delegating model
  // dispatches by provider. classifierClient is the only qualified client.

  @Bean
  @Qualifier("classifierClient")
  ChatClient classifierClient(ChatModel chatModel,
                              ClassifierProperties classifierProperties,
                              ModelRegistryProperties modelProperties) {
    String modelId = classifierProperties.getModelId();
    if (modelId == null || modelId.isBlank()) {
      modelId = resolveModelId(modelProperties, ModelTier.CLOUD_ENTRY);
    }
    return ChatClient.builder(chatModel)
        .defaultOptions(ChatOptions.builder()
            .model(modelId)
            .temperature(classifierProperties.getTemperature()))
        .build();
  }

  static String resolveModelId(ModelRegistryProperties properties,
                               ModelTier tier) {
    return properties.getRegistry().values().stream()
        .filter(entry -> entry.getTier() == tier)
        .map(ModelRegistryProperties.ModelEntry::getModelId)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No model configured for tier " + tier));
  }
}
