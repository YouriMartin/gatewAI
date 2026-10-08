package io.github.yourimartin.gatewai.adapter.in.web.chat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.LlmStreamChunk;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.StreamChatCompletionUseCase;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChatCompletionController {

  private static final long SSE_TIMEOUT_MS = 600_000L;

  /** What the semantic cache did: {@code HIT}, {@code MISS} or {@code BYPASS} (ADR 0014). */
  static final String CACHE_HEADER = "X-GatewAI-Cache";

  /** The model that produced the answer — on a hit, the one that produced it first. */
  static final String MODEL_HEADER = "X-GatewAI-Model";

  private final ChatCompletionUseCase useCase;
  private final StreamChatCompletionUseCase streamUseCase;

  ChatCompletionController(ChatCompletionUseCase useCase,
                          StreamChatCompletionUseCase streamUseCase) {
    this.useCase = useCase;
    this.streamUseCase = streamUseCase;
  }

  @PostMapping("/v1/chat/completions")
  Object complete(@RequestBody ChatCompletionRequest request) {
    LlmRequest llmRequest = OpenAiMapper.toLlmRequest(request);
    // Before any SSE is opened, so a refused stream is a 400, not an error event.
    llmRequest.requireServableByChain();
    if (Boolean.TRUE.equals(request.stream())) {
      return stream(llmRequest, request.includeUsage());
    }
    LlmResponse llmResponse = useCase.complete(llmRequest);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (llmResponse.cacheOutcome() != null) {
      response.header(CACHE_HEADER, llmResponse.cacheOutcome());
    }
    if (llmResponse.model() != null) {
      response.header(MODEL_HEADER, llmResponse.model());
    }
    return response.body(OpenAiMapper.toCompletionResponse(llmResponse));
  }

  private SseEmitter stream(LlmRequest llmRequest, boolean includeUsage) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    String id = "chatcmpl-" + UUID.randomUUID();
    long created = Instant.now().getEpochSecond();

    // Capture the request context now (Scoped Value still bound on this thread);
    // the worker re-binds it so cache namespacing + green accounting see clientId.
    RequestContext context = RequestContext.CURRENT.isBound()
        ? RequestContext.CURRENT.get() : null;

    Runnable task = () -> {
      try {
        AtomicReference<LlmStreamChunk> usage = new AtomicReference<>();
        streamUseCase.streamComplete(llmRequest, chunk -> {
          if (chunk.totalTokens() > 0 || usage.get() == null) {
            usage.set(chunk);
          }
          send(emitter, OpenAiMapper.toChunk(id, created, chunk));
        });
        if (includeUsage) {
          send(emitter, OpenAiMapper.toUsageChunk(id, created, usage.get()));
        }
        emitter.send(SseEmitter.event().data("[DONE]"));
        emitter.complete();
      } catch (Exception e) {
        emitter.completeWithError(e);
      }
    };

    Thread.ofVirtual().name("sse-chat").start(context == null
        ? task
        : () -> ScopedValue.where(RequestContext.CURRENT, context).run(task));
    return emitter;
  }

  private static void send(SseEmitter emitter, ChatCompletionChunk chunk) {
    try {
      emitter.send(SseEmitter.event().data(chunk, MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
