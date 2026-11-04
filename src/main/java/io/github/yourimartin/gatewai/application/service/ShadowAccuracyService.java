package io.github.yourimartin.gatewai.application.service;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.shadow.WrongHitEstimate;
import io.github.yourimartin.gatewai.domain.port.in.ShadowAccuracyUseCase;
import io.github.yourimartin.gatewai.domain.port.out.WouldHitHistory;

import org.springframework.stereotype.Service;

/** Wrong-hit estimates from the stored would-hit assessments (v4 D.2). */
@Service
class ShadowAccuracyService implements ShadowAccuracyUseCase {

  private final WouldHitHistory history;

  ShadowAccuracyService(WouldHitHistory history) {
    this.history = history;
  }

  @Override
  public List<WrongHitEstimate> wrongHitRates(Instant from, Instant to) {
    if (from == null || to == null || !from.isBefore(to)) {
      throw new IllegalArgumentException("'from' must be before 'to'");
    }
    return history.counts(from, to).stream().map(WrongHitEstimate::of).toList();
  }
}
