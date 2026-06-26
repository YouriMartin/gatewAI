package com.example.gatewai;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
class ChatClientSmokeTest {

  @Autowired
  private ChatClient.Builder chatClientBuilder;

  @Test
  void chatClientReturnsNonEmptyResponse() {
    ChatClient chatClient = chatClientBuilder.build();

    String response = chatClient
        .prompt()
        .user("Reply with exactly one word: hello")
        .call()
        .content();

    assertFalse(response == null || response.isBlank(),
        "ChatClient should return a non-blank response");
  }
}
