package com.example.gatewai.domain.port.in;

import java.util.UUID;

import com.example.gatewai.domain.model.LlmRequest;

/** Submits a request for carbon-aware deferred execution. */
public interface SubmitDeferredRequestUseCase {

  /**
   * Queues the request and returns immediately.
   *
   * @param request the LLM request to run later
   * @return the id of the created job
   */
  UUID submit(LlmRequest request);
}
