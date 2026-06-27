package com.example.gatewai.domain.model;

/** Lifecycle of a carbon-aware deferred request. */
public enum DeferredJobStatus {

  /** Submitted and waiting in the queue. */
  QUEUED,

  /** Picked up by the dispatch worker and executing. */
  RUNNING,

  /** Finished successfully; a result is available. */
  COMPLETED,

  /** Execution failed; an error message is available. */
  FAILED
}
