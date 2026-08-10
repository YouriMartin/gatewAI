package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;

import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drops decisions past their retention window (v2 batch 2).
 *
 * <p>Failures are logged and swallowed: a purge that cannot run is an
 * operational problem, not a reason to take the gateway down at the next tick.
 */
@Component
@ConditionalOnProperty(prefix = "gatewai.decisions", name = "enabled",
    matchIfMissing = true)
class DecisionPurgeWorker {

  private static final Logger LOG =
      LoggerFactory.getLogger(DecisionPurgeWorker.class);

  private final DecisionRecorder recorder;
  private final DecisionRecordingProperties properties;

  DecisionPurgeWorker(DecisionRecorder recorder,
                      DecisionRecordingProperties properties) {
    this.recorder = recorder;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString =
      "${gatewai.decisions.purge-interval-ms:86400000}",
      initialDelayString = "${gatewai.decisions.purge-interval-ms:86400000}")
  void purge() {
    Instant cutoff = Instant.now()
        .minus(Duration.ofDays(properties.getRetentionDays()));
    try {
      int removed = recorder.purgeOlderThan(cutoff);
      if (removed > 0) {
        LOG.info("Purged {} decision(s) older than {}", removed, cutoff);
      }
    } catch (RuntimeException e) {
      LOG.warn("Decision purge failed: {}", e.toString());
    }
  }

  /**
   * Enables scheduling for the purge. Separate from the dispatch worker's own
   * {@code @EnableScheduling} so either feature can run without the other.
   */
  @Configuration
  @EnableScheduling
  @ConditionalOnProperty(prefix = "gatewai.decisions", name = "enabled",
      matchIfMissing = true)
  static class SchedulingConfig {
  }
}
