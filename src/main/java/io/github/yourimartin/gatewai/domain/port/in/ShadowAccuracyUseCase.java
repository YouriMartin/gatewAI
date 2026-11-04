package io.github.yourimartin.gatewai.domain.port.in;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.shadow.WrongHitEstimate;

/** How often shadow would-hits would have been wrong, per client (v4 D.2). */
public interface ShadowAccuracyUseCase {

  /** One estimate per client, judge model and rubric over {@code [from, to)}. */
  List<WrongHitEstimate> wrongHitRates(Instant from, Instant to);
}
