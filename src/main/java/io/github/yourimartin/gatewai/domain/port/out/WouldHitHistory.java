package io.github.yourimartin.gatewai.domain.port.out;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.shadow.WouldHitCounts;

/**
 * Reads the would-hit assessments back, aggregated (v4 D.2). A separate port
 * from {@link DecisionRecorder} for the same reason as {@link DecisionHistory}:
 * writing must never block nor throw, reading is an admin query that may.
 */
public interface WouldHitHistory {

  /**
   * One group per client, judge model and rubric version, over {@code [from, to)}.
   * The would-hits nobody judged form a group of their own, with a null judge.
   */
  List<WouldHitCounts> counts(Instant from, Instant to);
}
