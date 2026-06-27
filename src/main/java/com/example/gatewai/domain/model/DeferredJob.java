package com.example.gatewai.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A request submitted for carbon-aware deferred execution. Immutable: state
 * transitions return a new instance so the store can replace the entry.
 *
 * @param id           unique job identifier
 * @param request      the LLM request to run later
 * @param clientId     tenant captured at submission (the worker has no scope)
 * @param status       current lifecycle status
 * @param result       the response once completed, otherwise {@code null}
 * @param chosenZone   grid zone selected at dispatch time, or {@code null}
 * @param errorMessage failure reason when {@code FAILED}, otherwise {@code null}
 * @param submittedAt  when the job was queued
 * @param completedAt  when the job reached a terminal state, or {@code null}
 */
public record DeferredJob(
    UUID id,
    LlmRequest request,
    String clientId,
    DeferredJobStatus status,
    LlmResponse result,
    String chosenZone,
    String errorMessage,
    Instant submittedAt,
    Instant completedAt
) {

  /** Creates a freshly queued job. */
  public static DeferredJob queued(UUID id, LlmRequest request,
                                   String clientId, Instant submittedAt) {
    return new DeferredJob(id, request, clientId, DeferredJobStatus.QUEUED,
        null, null, null, submittedAt, null);
  }

  /** Marks the job as running in the given (possibly null) zone. */
  public DeferredJob running(String zone) {
    return new DeferredJob(id, request, clientId, DeferredJobStatus.RUNNING,
        null, zone, null, submittedAt, null);
  }

  /** Marks the job as completed with its result. */
  public DeferredJob completed(LlmResponse response, Instant completedAt) {
    return new DeferredJob(id, request, clientId, DeferredJobStatus.COMPLETED,
        response, chosenZone, null, submittedAt, completedAt);
  }

  /** Marks the job as failed with an error message. */
  public DeferredJob failed(String error, Instant completedAt) {
    return new DeferredJob(id, request, clientId, DeferredJobStatus.FAILED,
        null, chosenZone, error, submittedAt, completedAt);
  }
}
