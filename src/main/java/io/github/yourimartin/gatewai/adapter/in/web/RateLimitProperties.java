package io.github.yourimartin.gatewai.adapter.in.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-client rate limiting on the LLM endpoints (Phase 6.2). */
@ConfigurationProperties(prefix = "gatewai.ratelimit")
class RateLimitProperties {

  private boolean enabled = true;

  /** Allowed requests per minute, per API client. */
  private int requestsPerMinute = 60;

  /**
   * Where the token buckets live (v3 lot B.3). {@code MEMORY} is per process, so
   * N replicas allow N × the limit; {@code POSTGRES} makes the limit the
   * cluster's, at the cost of a row lock per limited request.
   */
  private Store store = Store.MEMORY;

  boolean isEnabled() {
    return enabled;
  }

  void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  Store getStore() {
    return store;
  }

  void setStore(Store store) {
    this.store = store;
  }

  /** The available bucket stores. */
  enum Store {

    /** Heap, per process. Correct on one node, and only there. */
    MEMORY,

    /** Shared through the PostgreSQL the gateway already requires. */
    POSTGRES
  }

  int getRequestsPerMinute() {
    return requestsPerMinute;
  }

  void setRequestsPerMinute(int requestsPerMinute) {
    this.requestsPerMinute = requestsPerMinute;
  }
}
