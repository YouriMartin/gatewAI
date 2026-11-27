package io.github.yourimartin.gatewai.domain.port.in;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationTarget;

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

  /**
   * What governs {@code target} for one client (v4 D.3, ADR 0019): the client's
   * own calibration when it is valid, otherwise exactly {@link #state(CalibrationTarget)}.
   * Only the cache has client calibrations; for routing this is the global state.
   *
   * @param clientId the client, or null for the global state
   */
  CalibrationState state(CalibrationTarget target, String clientId);

  /** Every target, calibrated or not, with what is actually being applied. */
  List<CalibrationState> states();

  /**
   * Every client's own calibration (v4 D.3, ADR 0019), each with its own status:
   * a client row that is not {@code VALID} is listed, and is not what decides for
   * that client — the global state is.
   */
  List<CalibrationState> clientStates();

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

  /**
   * Fits the cache on the judge's verdicts on shadow would-hits and stores it
   * (v4 D.3, ADR 0019): one client's row, or the global row from every shadow
   * client's labels. Routing is not touched. Explicit like {@link #recalibrate}.
   *
   * @param clientId   the client to fit, or null to pool every shadow client into
   *                   the global row
   * @param cacheAlpha risk level, or null for the configured default
   * @param judgeModel the judge whose verdicts count, or null for the configured one
   * @return the state now in force for that scope
   * @throws IllegalArgumentException when α is outside (0, 1) or no judge is named
   * @throws IllegalStateException    when the labels cannot support the fit: below
   *                                  the minimum sample, or too few for that α
   */
  CalibrationState calibrateCacheOnShadow(String clientId, Double cacheAlpha,
                                          String judgeModel);

  /**
   * Removes a client's own cache calibration, returning it to the global one
   * (ADR 0019). It does not end the ratchet by itself: the next shadow fit for
   * that client counts only the labels recorded under the global threshold.
   *
   * @return whether the client had one
   */
  boolean removeClientCalibration(String clientId);
}
