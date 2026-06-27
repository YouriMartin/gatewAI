package com.example.gatewai.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.example.gatewai.domain.model.GreenAccountant;
import com.example.gatewai.domain.model.GreenMetrics;
import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.model.ModelDefinition;
import com.example.gatewai.domain.model.ModelTier;
import com.example.gatewai.domain.model.RequestContext;
import com.example.gatewai.domain.model.RequestLog;
import com.example.gatewai.domain.port.in.ChatCompletionUseCase;
import com.example.gatewai.domain.port.out.CarbonIntensityProvider;
import com.example.gatewai.domain.port.out.LlmClient;
import com.example.gatewai.domain.port.out.ModelRegistry;
import com.example.gatewai.domain.port.out.RequestLogRepository;

import org.springframework.stereotype.Service;

@Service
class ChatCompletionService implements ChatCompletionUseCase {

  private final LlmClient llmClient;
  private final RequestLogRepository requestLogRepository;
  private final ModelRegistry modelRegistry;
  private final CarbonIntensityProvider carbonIntensityProvider;
  private final GreenAccountant greenAccountant;

  ChatCompletionService(LlmClient llmClient,
                        RequestLogRepository requestLogRepository,
                        ModelRegistry modelRegistry,
                        CarbonIntensityProvider carbonIntensityProvider,
                        GreenAccountant greenAccountant) {
    this.llmClient = llmClient;
    this.requestLogRepository = requestLogRepository;
    this.modelRegistry = modelRegistry;
    this.carbonIntensityProvider = carbonIntensityProvider;
    this.greenAccountant = greenAccountant;
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
        green
    );
    requestLogRepository.save(log);

    return response;
  }

  private GreenMetrics accountGreen(LlmResponse response) {
    ModelDefinition used =
        modelRegistry.findByModelId(response.model()).orElse(null);
    ModelDefinition premiumBaseline =
        modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM).stream()
            .findFirst()
            .orElse(null);
    double gridIntensity = carbonIntensityProvider.gramsCo2PerKwh();

    return greenAccountant.account(
        used, premiumBaseline, response.totalTokens(), gridIntensity);
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
