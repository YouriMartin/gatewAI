package com.example.gatewai.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.model.RequestLog;
import com.example.gatewai.domain.port.in.ChatCompletionUseCase;
import com.example.gatewai.domain.port.out.LlmClient;
import com.example.gatewai.domain.port.out.RequestLogRepository;

import org.springframework.stereotype.Service;

@Service
class ChatCompletionService implements ChatCompletionUseCase {

  private final LlmClient llmClient;
  private final RequestLogRepository requestLogRepository;

  ChatCompletionService(LlmClient llmClient,
                        RequestLogRepository requestLogRepository) {
    this.llmClient = llmClient;
    this.requestLogRepository = requestLogRepository;
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    long startNanos = System.nanoTime();

    LlmResponse response = llmClient.call(request);

    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    String promptHash = hashPrompt(request);

    RequestLog log = new RequestLog(
        UUID.randomUUID(),
        Instant.now(),
        response.model(),
        promptHash,
        response.promptTokens(),
        response.completionTokens(),
        response.totalTokens(),
        latencyMs
    );
    requestLogRepository.save(log);

    return response;
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
