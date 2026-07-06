package io.github.yourimartin.gatewai.adapter.in.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.GreenReport;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.GenerateGreenReportUseCase;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GatewayMcpToolsTest {

  @Mock
  private GenerateGreenReportUseCase greenReportUseCase;
  @Mock
  private CarbonIntensityProvider carbonIntensityProvider;
  @Mock
  private ChatCompletionUseCase chatCompletionUseCase;

  private GatewayMcpTools tools;

  @BeforeEach
  void setUp() {
    tools = new GatewayMcpTools(
        greenReportUseCase, carbonIntensityProvider, chatCompletionUseCase);
  }

  @Test
  void greenReportMapsDomainReportAndDerivesCacheHitRate() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-02-01T00:00:00Z");
    GreenReport report = new GreenReport(
        from, to, 10, 4, 1.5, 0.5, 0.02, 3.0, 1.0, Map.of("haiku", 10L));
    when(greenReportUseCase.generate(from, to)).thenReturn(report);

    GreenReportToolResult result = tools.greenReport(
        "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");

    assertEquals(10, result.totalRequests());
    assertEquals(0.4, result.cacheHitRate(), 1e-9);
    assertEquals(3.0, result.totalGramsCo2(), 1e-9);
    assertEquals(Map.of("haiku", 10L), result.modelMix());
  }

  @Test
  void greenReportDefaultsToTrailingThirtyDayWindow() {
    when(greenReportUseCase.generate(any(), any())).thenReturn(new GreenReport(
        Instant.EPOCH, Instant.EPOCH, 0, 0, 0, 0, 0, 0, 0, Map.of()));

    tools.greenReport(null, null);

    ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
    verify(greenReportUseCase).generate(from.capture(), to.capture());
    Duration window = Duration.between(from.getValue(), to.getValue());
    assertEquals(Duration.ofDays(30), window);
  }

  @Test
  void carbonIntensityUsesDefaultZoneWhenBlank() {
    when(carbonIntensityProvider.gramsCo2PerKwh()).thenReturn(56.0);

    CarbonIntensityToolResult result = tools.carbonIntensity("  ");

    assertEquals("default", result.zone());
    assertEquals(56.0, result.gramsCo2PerKwh(), 1e-9);
  }

  @Test
  void carbonIntensityQueriesNamedZone() {
    when(carbonIntensityProvider.gramsCo2PerKwh("SE")).thenReturn(30.0);

    CarbonIntensityToolResult result = tools.carbonIntensity("SE");

    assertEquals("SE", result.zone());
    assertEquals(30.0, result.gramsCo2PerKwh(), 1e-9);
  }

  @Test
  void routedChatSendsSingleUserMessageWithoutForcingModel() {
    when(chatCompletionUseCase.complete(any())).thenReturn(new LlmResponse(
        "claude-haiku", "hi", "stop", 3, 1, 4, true));

    RoutedChatToolResult result = tools.routedChat("Hello", null);

    ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
    verify(chatCompletionUseCase).complete(request.capture());
    assertNull(request.getValue().model());
    assertEquals(1, request.getValue().messages().size());
    assertEquals("user", request.getValue().messages().getFirst().role());
    assertEquals("Hello", request.getValue().messages().getFirst().content());

    assertEquals("claude-haiku", result.model());
    assertEquals("hi", result.content());
    assertTrue(result.cacheHit());
    assertEquals(4, result.totalTokens());
  }

  @Test
  void routedChatForwardsRequestedModel() {
    when(chatCompletionUseCase.complete(any())).thenReturn(new LlmResponse(
        "claude-sonnet", "x", "stop", 1, 1, 2, false));

    tools.routedChat("Hello", " claude-sonnet ");

    ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
    verify(chatCompletionUseCase).complete(request.capture());
    assertEquals("claude-sonnet", request.getValue().model());
  }
}
