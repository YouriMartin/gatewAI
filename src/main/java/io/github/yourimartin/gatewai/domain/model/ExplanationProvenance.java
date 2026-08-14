package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;

/**
 * What an explanation is only valid for (v2 batch 9).
 *
 * <p><b>Never optional.</b> Every number in an explanation is relative to an
 * embedding model, a set of routing rules and a calibration; the same prompt
 * explained after a route edit gets different numbers and the same rendering.
 * Shipping the explanation without saying which world it came from is how a
 * traceability feature becomes a source of confident mistakes.
 *
 * @param embeddingModel       the model behind the vectors
 * @param routingConfigVersion hash of the routing rules
 * @param calibrationDate      when the routing calibration was fitted, null if
 *                             none ever was
 * @param calibrationStatus    whether that calibration is the one in force
 */
public record ExplanationProvenance(String embeddingModel,
                                    String routingConfigVersion,
                                    Instant calibrationDate,
                                    CalibrationStatus calibrationStatus) {

  /**
   * Provenance of a <b>stored</b> decision: the model and rules are the ones
   * recorded on the row, not today's. A decision taken before a route edit must
   * keep saying so — that is the entire point of versioning the row.
   */
  public static ExplanationProvenance of(RoutingDecision decision,
                                         CalibrationState routing) {
    return new ExplanationProvenance(
        decision.embeddingModel(), decision.routingConfigVersion(),
        calibratedAt(routing), routing == null ? null : routing.status());
  }

  /** Provenance of an on-the-fly explanation: everything is current. */
  public static ExplanationProvenance current(String embeddingModel,
                                              String routingConfigVersion,
                                              CalibrationState routing) {
    return new ExplanationProvenance(embeddingModel, routingConfigVersion,
        calibratedAt(routing), routing == null ? null : routing.status());
  }

  private static Instant calibratedAt(CalibrationState state) {
    return state == null || state.calibration() == null
        ? null : state.calibration().calibratedAt();
  }
}
