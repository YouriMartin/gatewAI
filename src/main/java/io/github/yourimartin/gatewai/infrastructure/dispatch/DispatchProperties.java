package io.github.yourimartin.gatewai.infrastructure.dispatch;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for carbon-aware deferred dispatch (Phase 4.4).
 *
 * <p>Disabled by default: the async endpoint still queues jobs, but the
 * scheduled worker only runs when {@code enabled=true}.
 */
@ConfigurationProperties(prefix = "gatewai.dispatch")
class DispatchProperties {

  private boolean enabled;

  /** Grid zones the worker compares to pick the greenest at execution time. */
  private List<String> candidateZones = new ArrayList<>();

  /** Worker poll interval, in milliseconds (also read by {@code @Scheduled}). */
  private long pollIntervalMs = 5000;

  boolean isEnabled() {
    return enabled;
  }

  void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  List<String> getCandidateZones() {
    return candidateZones;
  }

  void setCandidateZones(List<String> candidateZones) {
    this.candidateZones = candidateZones;
  }

  long getPollIntervalMs() {
    return pollIntervalMs;
  }

  void setPollIntervalMs(long pollIntervalMs) {
    this.pollIntervalMs = pollIntervalMs;
  }
}
