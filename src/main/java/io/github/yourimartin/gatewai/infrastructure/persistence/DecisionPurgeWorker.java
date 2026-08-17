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
 *
 * <p>Gated on a {@link LeaderLock} since v3 lot B.4, so N replicas purge once per
 * interval rather than N times. Purging twice was never <em>wrong</em> — the
 * second pass deletes nothing — but it is duplicated work on the same rows, and
 * two nodes each logging "purged 4 000 decisions" describes something that
 * happened once. A node that loses the race skips the tick and takes the next
 * one; a node that dies holding the lock releases it with its connection.
 */
@Component
@ConditionalOnProperty(prefix = "gatewai.decisions", name = "enabled",
    matchIfMissing = true)
class DecisionPurgeWorker {

  private static final Logger LOG =
      LoggerFactory.getLogger(DecisionPurgeWorker.class);

  private final DecisionRecorder recorder;
  private final DecisionRecordingProperties properties;
  private final LeaderLock leaderLock;

  DecisionPurgeWorker(DecisionRecorder recorder,
                      DecisionRecordingProperties properties,
                      LeaderLock leaderLock) {
    this.recorder = recorder;
    this.properties = properties;
    this.leaderLock = leaderLock;
  }

  @Scheduled(fixedDelayString =
      "${gatewai.decisions.purge-interval-ms:86400000}",
      initialDelayString = "${gatewai.decisions.purge-interval-ms:86400000}")
  void purge() {
    try {
      leaderLock.runIfLeader(LeaderTask.DECISION_PURGE, this::purgeNow);
    } catch (RuntimeException e) {
      // Outside the lock call, so a failure to *take* the lock is swallowed too:
      // either way the next tick tries again.
      LOG.warn("Decision purge failed: {}", e.toString());
    }
  }

  private void purgeNow() {
    Instant cutoff = Instant.now()
        .minus(Duration.ofDays(properties.getRetentionDays()));
    int removed = recorder.purgeOlderThan(cutoff);
    if (removed > 0) {
      LOG.info("Purged {} decision(s) older than {}", removed, cutoff);
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
