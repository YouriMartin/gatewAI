package com.example.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.gatewai.domain.model.LlmMessage;
import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.port.out.LlmClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatCompletionServiceTest {

  @Mock
  private LlmClient llmClient;

  @InjectMocks
  private ChatCompletionService service;

  @Test
  void completeDelegatesToLlmClient() {
    LlmRequest request = new LlmRequest(
        "claude-3", List.of(new LlmMessage("user", "hello")), 0.7, 256);
    LlmResponse expected = new LlmResponse(
        "claude-3", "Hi there!", "end_turn", 10, 5, 15);

    when(llmClient.call(request)).thenReturn(expected);

    LlmResponse actual = service.complete(request);

    assertSame(expected, actual);
    verify(llmClient).call(request);
  }
}
