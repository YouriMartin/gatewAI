package com.example.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.gatewai.domain.model.ModelTier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

@ExtendWith(MockitoExtension.class)
class ChatClientConfigurationTest {

  @Mock
  private ChatModel chatModel;

  @Test
  void resolveModelIdReturnsPremiumModelId() {
    ModelRegistryProperties props = buildProperties();

    String modelId = ChatClientConfiguration.resolveModelId(
        props, ModelTier.CLOUD_PREMIUM);

    assertEquals("claude-sonnet-4-20250514", modelId);
  }

  @Test
  void resolveModelIdReturnsEntryModelId() {
    ModelRegistryProperties props = buildProperties();

    String modelId = ChatClientConfiguration.resolveModelId(
        props, ModelTier.CLOUD_ENTRY);

    assertEquals("claude-haiku-4-20250506", modelId);
  }

  @Test
  void resolveModelIdThrowsWhenTierNotConfigured() {
    ModelRegistryProperties empty = new ModelRegistryProperties();

    IllegalStateException ex = assertThrows(
        IllegalStateException.class,
        () -> ChatClientConfiguration.resolveModelId(
            empty, ModelTier.CLOUD_PREMIUM));

    assertEquals("No model configured for tier CLOUD_PREMIUM",
        ex.getMessage());
  }

  @Test
  void premiumClientBeanIsCreated() {
    ChatClientConfiguration config = new ChatClientConfiguration();
    ModelRegistryProperties props = buildProperties();

    ChatClient client = config.premiumClient(chatModel, props);

    assertNotNull(client);
  }

  @Test
  void cheapCloudClientBeanIsCreated() {
    ChatClientConfiguration config = new ChatClientConfiguration();
    ModelRegistryProperties props = buildProperties();

    ChatClient client = config.cheapCloudClient(chatModel, props);

    assertNotNull(client);
  }

  private static ModelRegistryProperties buildProperties() {
    ModelRegistryProperties props = new ModelRegistryProperties();
    Map<String, ModelRegistryProperties.ModelEntry> entries =
        new LinkedHashMap<>();

    ModelRegistryProperties.ModelEntry sonnet =
        new ModelRegistryProperties.ModelEntry();
    sonnet.setProvider("anthropic");
    sonnet.setModelId("claude-sonnet-4-20250514");
    sonnet.setCostPer1kTokens(0.015);
    sonnet.setEnergyIntensity(0.6);
    sonnet.setTier(ModelTier.CLOUD_PREMIUM);
    entries.put("claude-sonnet", sonnet);

    ModelRegistryProperties.ModelEntry haiku =
        new ModelRegistryProperties.ModelEntry();
    haiku.setProvider("anthropic");
    haiku.setModelId("claude-haiku-4-20250506");
    haiku.setCostPer1kTokens(0.002);
    haiku.setEnergyIntensity(0.15);
    haiku.setTier(ModelTier.CLOUD_ENTRY);
    entries.put("claude-haiku", haiku);

    props.setRegistry(entries);
    return props;
  }
}
