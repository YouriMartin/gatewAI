package com.example.gatewai.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.example.gatewai.domain.model.CarbonZoneContext;
import com.example.gatewai.domain.model.GreenAccountant;
import com.example.gatewai.domain.model.GreenMetrics;
import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.model.LlmStreamChunk;
import com.example.gatewai.domain.model.ModelDefinition;
import com.example.gatewai.domain.model.ModelTier;
import com.example.gatewai.domain.model.RequestContext;
import com.example.gatewai.domain.model.RequestLog;
import com.example.gatewai.domain.port.in.ChatCompletionUseCase;
import com.example.gatewai.domain.port.in.StreamChatCompletionUseCase;
import com.example.gatewai.domain.port.out.CarbonIntensityProvider;
import com.example.gatewai.domain.port.out.LlmClient;
import com.example.gatewai.domain.port.out.MetricsRecorder;
import com.example.gatewai.domain.port.out.ModelRegistry;
import com.example.gatewai.domain.port.out.RequestLogRepository;

import org.springframework.stereotype.Service;

@Service
class ChatCompletionService
    implements ChatCompletionUseCase, StreamChatCompletionUseCase {

  private final LlmClient llmClient;
  private final RequestLogRepository requestLogRepository;
  private final ModelRegistry modelRegistry;
  private final CarbonIntensityProvider carbonIntensityProvider;
  private final GreenAccountant greenAccountant;
  private final MetricsRecorder metricsRecorder;

  ChatCompletionService(LlmClient llmClient,
                        RequestLogRepository requestLogRepository,
                        ModelRegistry modelRegistry,
                        CarbonIntensityProvider carbonIntensityProvider,
                        GreenAccountant greenAccountant,
                        MetricsRecorder metricsRecorder) {
    this.llmClient = llmClient;
    this.requestLogRepository = requestLogRepository;
    this.modelRegistry = modelRegistry;
    this.carbonIntensityProvider = carbonIntensityProvider;
    this.greenAccountant = greenAccountant;
    this.metricsRecorder = metricsRecorder;
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    long startNanos = System.nanoTime();

    LlmResponse response = llmClient.call(request);

    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    String promptHash = hashPrompt(request);
    String clientId = resolveClientId();
    GreenMetrics green = accountGreen(response);

    RequestLog log = new RequestLog(
        UUID.randomUUID(),
        Instant.now(),
        response.model(),
        promptHash,
        response.promptTokens(),
        response.completionTokens(),
        response.totalTokens(),
        latencyMs,
        clientId,
        green,
        response.cacheHit()
    );
    requestLogRepository.save(log);
    metricsRecorder.record(log);

    return response;
  }

  /**
   * Streams the response (Phase 7.5). Forwards each chunk to {@code onChunk} and,
   * once the stream completes, records the same green accounting / persistence /
   * metrics as the blocking path — from the terminal chunk's model + token usage.
   */
  @Override
  public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> onChunk) {
    long startNanos = System.nanoTime();
    String clientId = resolveClientId();
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
    GreenMetrics green =
        accountGreen(last.model(), last.totalTokens(), last.cacheHit());

    RequestLog log = new RequestLog(
        UUID.randomUUID(),
        Instant.now(),
        last.model(),
        hashPrompt(request),
        last.promptTokens(),
        last.completionTokens(),
        last.totalTokens(),
        latencyMs,
        clientId,
        green,
        last.cacheHit()
    );
    requestLogRepository.save(log);
    metricsRecorder.record(log);
  }

  private GreenMetrics accountGreen(LlmResponse response) {
    return accountGreen(response.model(), response.totalTokens(), response.cacheHit());
  }

  private GreenMetrics accountGreen(String model, int totalTokens, boolean cacheHit) {
    ModelDefinition used = modelRegistry.findByModelId(model).orElse(null);
    ModelDefinition premiumBaseline =
        modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM).stream()
            .findFirst()
            .orElse(null);
    double gridIntensity = CarbonZoneContext.CURRENT.isBound()
        ? carbonIntensityProvider.gramsCo2PerKwh(CarbonZoneContext.CURRENT.get())
        : carbonIntensityProvider.gramsCo2PerKwh();

    return greenAccountant.account(
        used, premiumBaseline, totalTokens, gridIntensity, cacheHit);
  }

  private static String resolveClientId() {
    if (RequestContext.CURRENT.isBound()) {
      return RequestContext.CURRENT.get().clientId();
    }
    return null;
  }

  static String hashPrompt(LlmRequest request) {
    MessageDigest digest = sha256();
    request.messages().forEach(msg -> {
      digest.update(msg.role().getBytes(StandardCharsets.UTF_8));
      digest.update(msg.content().getBytes(StandardCharsets.UTF_8));
    });
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is guaranteed by the JDK", e);
    }
  }
}
