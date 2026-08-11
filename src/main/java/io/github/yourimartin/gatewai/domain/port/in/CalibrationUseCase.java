package io.github.yourimartin.gatewai.domain.port.in;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;

/**
 * Reads and recomputes the conformal calibrations (v2 batch 3).
 *
 * <p>Also queried on the request path — the cache advisor and the router ask
 * {@link #state(CalibrationTarget)} which threshold is in force. That query is
 * served from an in-memory snapshot, never from the database, so a calibration
 * lookup cannot put a query between a request and its answer.
 */
public interface CalibrationUseCase {

  /**
   * What governs {@code target} right now: the threshold in force, whether it
   * came from a calibration, and if not, why not.
   *
   * <p>One call rather than "is there one?" followed by "what is it?", because
   * the caller needs the reason too — a decision taken under a stale calibration
   * and one taken before any calibration existed are both degraded, and the
   * trace has to tell them apart.
   */
  CalibrationState state(CalibrationTarget target);

  /** Every target, calibrated or not, with what is actually being applied. */
  List<CalibrationState> states();

  /**
   * Recomputes both calibrations from the labelled set and stores them.
   *
   * <p>Expensive and explicit: it embeds the labelled cache pairs and classifies
   * every labelled prompt, so it takes tens of seconds. Never automatic —
   * a threshold must not move because a scheduler woke up.
   *
   * @param routingAlpha risk level for routing, or null for the configured default
   * @param cacheAlpha   risk level for the cache, or null for the configured default
   */
  List<CalibrationState> recalibrate(Double routingAlpha, Double cacheAlpha);
}
