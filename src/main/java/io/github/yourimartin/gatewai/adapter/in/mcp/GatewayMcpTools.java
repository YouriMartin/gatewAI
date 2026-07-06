package io.github.yourimartin.gatewai.adapter.in.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.GreenReport;
import io.github.yourimartin.gatewai.domain.model.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.GenerateGreenReportUseCase;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * The gateway's capabilities exposed as MCP tools (Phase 6.4). Each method is a
 * thin ingress over an existing inbound use case / outbound port — no business
 * logic lives here, so the green caching/routing/accounting chain stays the
 * single source of truth.
 */
@Component
class GatewayMcpTools {

  /** Default reporting window when the caller omits a date range. */
  private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

  private final GenerateGreenReportUseCase greenReportUseCase;
  private final CarbonIntensityProvider carbonIntensityProvider;
  private final ChatCompletionUseCase chatCompletionUseCase;

  // @Lazy breaks a startup bean cycle: the MCP ToolCallbackProvider is collected
  // by Spring AI's tool-calling auto-config into the ChatModel, while this tool's
  // routed_chat depends (transitively) on that same ChatModel. A lazy proxy defers
  // resolving ChatCompletionUseCase until the first tool call (request time).
  GatewayMcpTools(GenerateGreenReportUseCase greenReportUseCase,
                  CarbonIntensityProvider carbonIntensityProvider,
                  @Lazy ChatCompletionUseCase chatCompletionUseCase) {
    this.greenReportUseCase = greenReportUseCase;
    this.carbonIntensityProvider = carbonIntensityProvider;
    this.chatCompletionUseCase = chatCompletionUseCase;
  }

  @Tool(name = "green_report",
      description = "Aggregated AI cost (EUR) and carbon footprint (gCO2) served "
          + "by the gateway over a date range: money and emissions actually "
          + "spent, money and emissions avoided versus a premium baseline, "
          + "semantic-cache hit rate and per-model request mix.")
  GreenReportToolResult greenReport(
      @ToolParam(required = false,
          description = "Inclusive start, ISO-8601 instant (e.g. "
              + "2026-01-01T00:00:00Z). Defaults to 30 days before 'to'.")
      String fromIso,
      @ToolParam(required = false,
          description = "Inclusive end, ISO-8601 instant. Defaults to now.")
      String toIso) {
    Instant to = parseOrDefault(toIso, Instant.now());
    Instant from = parseOrDefault(fromIso, to.minus(DEFAULT_WINDOW));
    GreenReport report = greenReportUseCase.generate(from, to);
    return GreenReportToolResult.from(report);
  }

  @Tool(name = "carbon_intensity",
      description = "Current electricity-grid carbon intensity (gCO2-eq per kWh) "
          + "for a grid zone, as used to turn estimated energy into emissions.")
  CarbonIntensityToolResult carbonIntensity(
      @ToolParam(required = false,
          description = "Grid zone id such as FR, SE or DE. Defaults to the "
              + "gateway's configured zone.")
      String zone) {
    if (zone == null || zone.isBlank()) {
      return new CarbonIntensityToolResult(
          "default", carbonIntensityProvider.gramsCo2PerKwh());
    }
    String trimmed = zone.trim();
    return new CarbonIntensityToolResult(
        trimmed, carbonIntensityProvider.gramsCo2PerKwh(trimmed));
  }

  @Tool(name = "routed_chat",
      description = "Complete a prompt through the Green AI gateway: semantic "
          + "cache, cost/carbon-aware model routing and green accounting. "
          + "Returns the answer plus which model actually served it, whether it "
          + "was a cache hit, and token usage.")
  RoutedChatToolResult routedChat(
      @ToolParam(description = "The user prompt to complete.")
      String prompt,
      @ToolParam(required = false,
          description = "Optional preferred model id; the router may override "
              + "it based on the prompt's complexity.")
      String model) {
    String requestedModel = (model == null || model.isBlank()) ? null : model.trim();
    LlmRequest request = new LlmRequest(
        requestedModel,
        List.of(new LlmMessage("user", prompt)),
        null,
        null);
    LlmResponse response = chatCompletionUseCase.complete(request);
    return RoutedChatToolResult.from(response);
  }

  private static Instant parseOrDefault(String iso, Instant fallback) {
    if (iso == null || iso.isBlank()) {
      return fallback;
    }
    return Instant.parse(iso.trim());
  }
}
