package io.github.yourimartin.gatewai.adapter.in.web;

import java.util.Locale;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.DeferredJob;
import io.github.yourimartin.gatewai.domain.port.in.GetDeferredJobUseCase;
import io.github.yourimartin.gatewai.domain.port.in.SubmitDeferredRequestUseCase;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Carbon-aware asynchronous completions (Phase 4.4). Submission queues the
 * request and returns {@code 202} with a job id; the result is polled later.
 */
@RestController
class AsyncChatCompletionController {

  private final SubmitDeferredRequestUseCase submitUseCase;
  private final GetDeferredJobUseCase getUseCase;

  AsyncChatCompletionController(SubmitDeferredRequestUseCase submitUseCase,
                               GetDeferredJobUseCase getUseCase) {
    this.submitUseCase = submitUseCase;
    this.getUseCase = getUseCase;
  }

  @PostMapping("/v1/chat/completions/async")
  ResponseEntity<DeferredJobResponse> submit(
      @RequestBody ChatCompletionRequest request) {
    UUID id = submitUseCase.submit(OpenAiMapper.toLlmRequest(request));
    return ResponseEntity.accepted()
        .body(new DeferredJobResponse(id.toString(), "queued", null, null, null));
  }

  @GetMapping("/v1/chat/completions/async/{id}")
  ResponseEntity<DeferredJobResponse> get(@PathVariable String id) {
    UUID jobId;
    try {
      jobId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    }
    return getUseCase.find(jobId)
        .map(job -> ResponseEntity.ok(toResponse(job)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static DeferredJobResponse toResponse(DeferredJob job) {
    ChatCompletionResponse result = job.result() == null
        ? null : OpenAiMapper.toCompletionResponse(job.result());
    return new DeferredJobResponse(
        job.id().toString(),
        job.status().name().toLowerCase(Locale.ROOT),
        job.chosenZone(),
        result,
        job.errorMessage()
    );
  }
}
