package io.github.yourimartin.gatewai;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end egress smoke: sends one real prompt through the delegating chat
 * model. Provider-agnostic — set {@code GATEWAI_SMOKE_MODEL} to any model id
 * declared in the registry (e.g. {@code qwen2.5:0.5b} for the default local
 * egress, or a Claude id if you routed a tier to Anthropic). Skipped when the
 * variable is unset, so CI never makes a provider call.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "GATEWAI_SMOKE_MODEL", matches = ".+")
@SpringBootTest
class ChatClientSmokeTest {

  @Autowired
  private ChatClient.Builder chatClientBuilder;

  @Test
  void chatClientReturnsNonEmptyResponse() {
    ChatClient chatClient = chatClientBuilder.build();

    String response = chatClient
        .prompt()
        .options(ChatOptions.builder()
            .model(System.getenv("GATEWAI_SMOKE_MODEL")))
        .user("Reply with exactly one word: hello")
        .call()
        .content();

    assertFalse(response == null || response.isBlank(),
        "ChatClient should return a non-blank response");
  }
}
