package io.github.yourimartin.gatewai.infrastructure.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Decision tracing: whether to record, and for how long (v2 batch 2). */
@ConfigurationProperties(prefix = "gatewai.decisions")
class DecisionRecordingProperties {

  /** Record routing and cache decisions. Off means no trace at all. */
  private boolean enabled = true;

  /**
   * How long a decision is kept. Decisions are operational telemetry, not
   * business records: they hold no prompt text, only hashes and scores.
   */
  private int retentionDays = 90;

  /** How often the purge runs, in milliseconds. Daily by default. */
  private long purgeIntervalMs = 86_400_000L;

  boolean isEnabled() {
    return enabled;
  }

  void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  int getRetentionDays() {
    return retentionDays;
  }

  void setRetentionDays(int retentionDays) {
    this.retentionDays = retentionDays;
  }

  long getPurgeIntervalMs() {
    return purgeIntervalMs;
  }

  void setPurgeIntervalMs(long purgeIntervalMs) {
    this.purgeIntervalMs = purgeIntervalMs;
  }
}
