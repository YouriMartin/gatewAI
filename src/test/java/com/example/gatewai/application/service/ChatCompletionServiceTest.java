package com.example.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.gatewai.domain.model.LlmMessage;
import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.model.RequestContext;
import com.example.gatewai.domain.model.RequestLog;
import com.example.gatewai.domain.port.out.LlmClient;
import com.example.gatewai.domain.port.out.RequestLogRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatCompletionServiceTest {

  @Mock
  private LlmClient llmClient;

  @Mock
  private RequestLogRepository requestLogRepository;

  @InjectMocks
  private ChatCompletionService service;

  @Captor
  private ArgumentCaptor<RequestLog> logCaptor;

  @Test
  void completeDelegatesToLlmClient() {
    LlmRequest request = new LlmRequest(
        "claude-3", List.of(new LlmMessage("user", "hello")), 0.7, 256);
    LlmResponse expected = new LlmResponse(
        "claude-3", "Hi there!", "end_turn", 10, 5, 15);

    when(llmClient.call(request)).thenReturn(expected);
    doNothing().when(requestLogRepository).save(any());

    LlmResponse actual = service.complete(request);

    assertSame(expected, actual);
    verify(llmClient).call(request);
  }

  @Test
  void completeSavesRequestLog() {
    LlmRequest request = new LlmRequest(
        "claude-3", List.of(new LlmMessage("user", "hello")), 0.7, 256);
    LlmResponse response = new LlmResponse(
        "claude-3-real", "Hi!", "end_turn", 12, 8, 20);

    when(llmClient.call(request)).thenReturn(response);
    doNothing().when(requestLogRepository).save(any());

    service.complete(request);

    verify(requestLogRepository).save(logCaptor.capture());
    RequestLog log = logCaptor.getValue();

    assertEquals("claude-3-real", log.model());
    assertEquals(12, log.promptTokens());
    assertEquals(8, log.completionTokens());
    assertEquals(20, log.totalTokens());
    assertEquals(64, log.promptHash().length());
    assertTrue(log.latencyMs() >= 0);
  }

  @Test
  void samePromptProducesSameHash() {
    LlmRequest request1 = new LlmRequest(
        "claude-3", List.of(new LlmMessage("user", "hello")), 0.7, 256);
    LlmRequest request2 = new LlmRequest(
        "claude-3", List.of(new LlmMessage("user", "hello")), 0.9, 512);

    String hash1 = ChatCompletionService.hashPrompt(request1);
    String hash2 = ChatCompletionService.hashPrompt(request2);

    assertEquals(hash1, hash2);
    assertEquals(64, hash1.length());
  }

  @Test
  void clientIdIsNullWhenScopedValueNotBound() {
    LlmRequest request = new LlmRequest(
        "claude-3", List.of(new LlmMessage("user", "hello")), 0.7, 256);
    LlmResponse response = new LlmResponse(
        "claude-3", "Hi!", "end_turn", 10, 5, 15);

    when(llmClient.call(request)).thenReturn(response);
    doNothing().when(requestLogRepository).save(any());

    service.complete(request);

    verify(requestLogRepository).save(logCaptor.capture());
    assertNull(logCaptor.getValue().clientId());
  }

  @Test
  void clientIdIsCapturedFromScopedValue() {
    LlmRequest request = new LlmRequest(
        "claude-3", List.of(new LlmMessage("user", "hello")), 0.7, 256);
    LlmResponse response = new LlmResponse(
        "claude-3", "Hi!", "end_turn", 10, 5, 15);

    when(llmClient.call(request)).thenReturn(response);
    doNothing().when(requestLogRepository).save(any());

    RequestContext ctx = new RequestContext("tenant-42", "trace-1");
    ScopedValue.where(RequestContext.CURRENT, ctx).run(() ->
        service.complete(request)
    );

    verify(requestLogRepository).save(logCaptor.capture());
    assertEquals("tenant-42", logCaptor.getValue().clientId());
  }
}
