package io.github.yourimartin.gatewai;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.FailoverEvent;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.shadow.WouldHitAssessment;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;

/** A recorder that keeps nothing; a test overrides the one kind it collects. */
public class NoOpDecisionRecorder implements DecisionRecorder {

  @Override
  public void record(RoutingDecision decision) {
    // Not kept.
  }

  @Override
  public void record(CacheDecision decision) {
    // Not kept.
  }

  @Override
  public void record(FailoverEvent event) {
    // Not kept.
  }

  @Override
  public void record(WouldHitAssessment assessment) {
    // Not kept.
  }

  @Override
  public int purgeOlderThan(Instant cutoff) {
    return 0;
  }
}
