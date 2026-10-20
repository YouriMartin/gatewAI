package io.github.yourimartin.gatewai.application.service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.github.yourimartin.gatewai.domain.model.carbon.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.carbon.ResolvedCarbonZone;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.LlmStreamChunk;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.StreamChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.LlmClient;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;

import org.springframework.stereotype.Service;

@Service
class ChatCompletionService
    implements ChatCompletionUseCase, StreamChatCompletionUseCase {

  private final LlmClient llmClient;
  private final RequestAccounting accounting;

  ChatCompletionService(LlmClient llmClient,
                        RequestLogRepository requestLogRepository,
                        ModelRegistry modelRegistry,
                        CarbonIntensityProvider carbonIntensityProvider,
                        GreenAccountant greenAccountant,
                        MetricsRecorder metricsRecorder,
                        ProviderRegions providerRegions,
                        CloudRegionZones cloudRegionZones) {
    this.llmClient = llmClient;
    this.accounting = new RequestAccounting(requestLogRepository, modelRegistry,
        carbonIntensityProvider, greenAccountant, metricsRecorder, providerRegions,
        cloudRegionZones);
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    request.requireServableByChain();
    long startNanos = System.nanoTime();

    LlmResponse response = llmClient.call(request);

    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    accounting.record(request, response.model(), new TokenUsage(
            response.promptTokens(), response.completionTokens(), response.totalTokens()),
        response.cacheHit(), latencyMs, RequestAccounting.correlationId(),
        RequestAccounting.clientId());
    return response;
  }

  /**
   * Streams the response (Phase 7.5). Forwards each chunk to {@code onChunk} and,
   * once the stream completes, records the same green accounting / persistence /
   * metrics as the blocking path — from the terminal chunk's model + token usage.
   */
  @Override
  public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> onChunk) {
    request.requireServableByChain();
    long startNanos = System.nanoTime();
    String clientId = RequestAccounting.clientId();
    String correlationId = RequestAccounting.correlationId();
    AtomicReference<LlmStreamChunk> lastChunk = new AtomicReference<>();

    llmClient.stream(request, chunk -> {
      lastChunk.set(chunk);
      onChunk.accept(chunk);
    });

    LlmStreamChunk last = lastChunk.get();
    if (last == null) {
      return;
    }

    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    accounting.record(request, last.model(), new TokenUsage(
            last.promptTokens(), last.completionTokens(), last.totalTokens()),
        last.cacheHit(), latencyMs, correlationId, clientId);
  }

  /**
   * Runs the resolution chain for one model. Package-visible so a test can assert
   * the zone <em>and</em> the recorded-but-not-applied dispatch zone, which is the
   * distinction lot C.5 will persist.
   */
  ResolvedCarbonZone resolveZone(ModelDefinition model) {
    return accounting.resolveZone(model);
  }

  static String hashPrompt(LlmRequest request) {
    return RequestAccounting.hashPrompt(request);
  }
}
