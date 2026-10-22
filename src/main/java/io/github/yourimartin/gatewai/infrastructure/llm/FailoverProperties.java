package io.github.yourimartin.gatewai.infrastructure.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Ordered failover inside a tier (ADR 0017, v4 B.5): {@code gatewai.failover.*}. */
@ConfigurationProperties(prefix = "gatewai.failover")
class FailoverProperties {

  /**
   * Whether a request pinned to a registered model may fail over to the rest of
   * that model's tier. Off by default: the client asked for that model.
   */
  private boolean pinned;

  /** Consecutive transient failures after which an entry cools down. */
  private int failureThreshold = 3;

  /** How long a cooling entry is skipped before one request probes it. */
  private Duration cooldown = Duration.ofSeconds(30);

  boolean isPinned() {
    return pinned;
  }

  void setPinned(boolean pinned) {
    this.pinned = pinned;
  }

  int getFailureThreshold() {
    return failureThreshold;
  }

  void setFailureThreshold(int failureThreshold) {
    this.failureThreshold = failureThreshold;
  }

  Duration getCooldown() {
    return cooldown;
  }

  void setCooldown(Duration cooldown) {
    this.cooldown = cooldown;
  }
}
