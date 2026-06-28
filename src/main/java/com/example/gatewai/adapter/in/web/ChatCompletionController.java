package com.example.gatewai.adapter.in.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;

import com.example.gatewai.domain.model.LlmRequest;
import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.model.LlmStreamChunk;
import com.example.gatewai.domain.model.RequestContext;
import com.example.gatewai.domain.port.in.ChatCompletionUseCase;
import com.example.gatewai.domain.port.in.StreamChatCompletionUseCase;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class ChatCompletionController {

  private static final long SSE_TIMEOUT_MS = 600_000L;

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
    if (Boolean.TRUE.equals(request.stream())) {
      return stream(llmRequest);
    }
    LlmResponse llmResponse = useCase.complete(llmRequest);
    return OpenAiMapper.toCompletionResponse(llmResponse);
  }

  private SseEmitter stream(LlmRequest llmRequest) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    String id = "chatcmpl-" + UUID.randomUUID();
    long created = Instant.now().getEpochSecond();

    // Capture the request context now (Scoped Value still bound on this thread);
    // the worker re-binds it so cache namespacing + green accounting see clientId.
    RequestContext context = RequestContext.CURRENT.isBound()
        ? RequestContext.CURRENT.get() : null;

    Runnable task = () -> {
      try {
        streamUseCase.streamComplete(llmRequest,
            chunk -> sendChunk(emitter, id, created, chunk));
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

  private static void sendChunk(SseEmitter emitter, String id, long created,
                                LlmStreamChunk chunk) {
    try {
      emitter.send(SseEmitter.event()
          .data(OpenAiMapper.toChunk(id, created, chunk), MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
