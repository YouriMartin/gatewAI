package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

class MockEchoChatModelTest {

  private final MockEchoChatModel model = new MockEchoChatModel();

  @Test
  void echoesUserTextAndPreservesRoutedModelAndTokens() {
    Prompt prompt = new Prompt(
        List.of(new UserMessage("hello there friend")),
        ChatOptions.builder().model("claude-haiku-4-5").build());

    ChatResponse response = model.call(prompt);

    assertEquals("claude-haiku-4-5", response.getMetadata().getModel());
    assertTrue(response.getResult().getOutput().getText().contains("hello there friend"));
    assertEquals(3, response.getMetadata().getUsage().getPromptTokens());
    assertTrue(response.getMetadata().getUsage().getCompletionTokens() > 0);
  }

  @Test
  void handlesMissingOptionsAndEmptyPrompt() {
    Prompt prompt = new Prompt(List.of(new UserMessage("   ")));

    ChatResponse response = model.call(prompt);

    assertEquals("mock", response.getMetadata().getModel());
    assertTrue(response.getResult().getOutput().getText().contains("empty prompt"));
  }
}
