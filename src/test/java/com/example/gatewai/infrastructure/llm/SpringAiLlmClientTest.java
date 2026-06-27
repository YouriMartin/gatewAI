package com.example.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.gatewai.domain.model.LlmMessage;
import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

@ExtendWith(MockitoExtension.class)
class SpringAiLlmClientTest {

  @Mock
  private ChatClient.Builder chatClientBuilder;
  @Mock
  private ChatClient chatClient;
  @Mock
  private ChatClient.ChatClientRequestSpec requestSpec;
  @Mock
  private ChatClient.CallResponseSpec callResponseSpec;

  @Captor
  private ArgumentCaptor<List<Message>> messagesCaptor;

  private SpringAiLlmClient llmClient;

  @BeforeEach
  void setUp() {
    when(chatClientBuilder.defaultAdvisors(anyList())).thenReturn(chatClientBuilder);
    when(chatClientBuilder.build()).thenReturn(chatClient);
    llmClient = new SpringAiLlmClient(chatClientBuilder, List.of());
  }

  private void stubFluentChain(ChatResponse response) {
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.messages(anyList())).thenReturn(requestSpec);
    when(requestSpec.options(any())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponseSpec);
    when(callResponseSpec.chatResponse()).thenReturn(response);
  }

  @Test
  void callMapsRolesToCorrectSpringAiMessageTypes() {
    stubFluentChain(buildChatResponse());

    LlmRequest request = new LlmRequest("claude-3",
        List.of(
            new LlmMessage("system", "Be helpful"),
            new LlmMessage("user", "Hello"),
            new LlmMessage("assistant", "Hi")
        ), null, null);

    llmClient.call(request);

    verify(requestSpec).messages(messagesCaptor.capture());
    List<Message> captured = messagesCaptor.getValue();
    assertEquals(3, captured.size());
    assertInstanceOf(SystemMessage.class, captured.get(0));
    assertInstanceOf(UserMessage.class, captured.get(1));
    assertInstanceOf(AssistantMessage.class, captured.get(2));
  }

  @Test
  void callExtractsResponseFieldsCorrectly() {
    stubFluentChain(buildChatResponse());

    LlmRequest request = new LlmRequest("claude-3",
        List.of(new LlmMessage("user", "Hello")), 0.7, 256);

    LlmResponse response = llmClient.call(request);

    assertEquals("claude-3-sonnet", response.model());
    assertEquals("Hello from Claude!", response.content());
    assertEquals("end_turn", response.finishReason());
    assertEquals(10, response.promptTokens());
    assertEquals(5, response.completionTokens());
    assertEquals(15, response.totalTokens());
  }

  private static ChatResponse buildChatResponse() {
    ChatGenerationMetadata genMeta = ChatGenerationMetadata.builder()
        .finishReason("end_turn")
        .build();

    Generation generation = new Generation(
        new AssistantMessage("Hello from Claude!"), genMeta);

    ChatResponseMetadata responseMeta = ChatResponseMetadata.builder()
        .model("claude-3-sonnet")
        .usage(new DefaultUsage(10, 5))
        .build();

    return new ChatResponse(List.of(generation), responseMeta);
  }
}
