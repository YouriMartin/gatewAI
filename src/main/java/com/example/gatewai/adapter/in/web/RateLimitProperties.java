package com.example.gatewai.adapter.in.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-client rate limiting on the LLM endpoints (Phase 6.2). */
@ConfigurationProperties(prefix = "gatewai.ratelimit")
class RateLimitProperties {

  private boolean enabled = true;

  /** Allowed requests per minute, per API client. */
  private int requestsPerMinute = 60;

  boolean isEnabled() {
    return enabled;
  }

  void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  int getRequestsPerMinute() {
    return requestsPerMinute;
  }

  void setRequestsPerMinute(int requestsPerMinute) {
    this.requestsPerMinute = requestsPerMinute;
  }
}
