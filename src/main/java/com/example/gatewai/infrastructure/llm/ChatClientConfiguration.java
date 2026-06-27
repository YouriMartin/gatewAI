package com.example.gatewai.infrastructure.llm;

import com.example.gatewai.domain.model.ModelTier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ChatClientConfiguration {

  @Bean
  @Qualifier("premiumClient")
  ChatClient premiumClient(ChatModel chatModel,
                           ModelRegistryProperties properties) {
    String modelId = resolveModelId(properties, ModelTier.CLOUD_PREMIUM);
    return ChatClient.builder(chatModel)
        .defaultOptions(ChatOptions.builder().model(modelId))
        .build();
  }

  @Bean
  @Qualifier("cheapCloudClient")
  ChatClient cheapCloudClient(ChatModel chatModel,
                              ModelRegistryProperties properties) {
    String modelId = resolveModelId(properties, ModelTier.CLOUD_ENTRY);
    return ChatClient.builder(chatModel)
        .defaultOptions(ChatOptions.builder().model(modelId))
        .build();
  }

  // Local client (Ollama) will be added here when
  // OllamaChatAutoConfiguration is re-enabled (requires a running
  // Ollama instance with a chat model pulled).

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
