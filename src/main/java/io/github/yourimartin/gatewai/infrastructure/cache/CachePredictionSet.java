package io.github.yourimartin.gatewai.infrastructure.cache;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationScope;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationStatus;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalStatus;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecisionReason;

import org.springframework.ai.document.Document;

/**
 * Applies the conformal prediction set to the candidates (v2 batch 3).
 *
 * <p>Under a valid calibration the threshold is {@code q̂}, fitted so that at
 * most α of the pairs labelled wrong are served — labelled by a person, or on a
 * shadow fit by the judge (ADR 0019) — and the <b>size</b> of the set decides:
 *
 * <ul>
 *   <li>empty — nothing is close enough: a miss;</li>
 *   <li>one — serve it;</li>
 *   <li>more than one — <b>do not serve</b>. If two stored answers both look
 *       right for this query, at most one of them is, and taking the higher
 *       score is guessing with the user's answer. Ambiguity is a risk signal,
 *       not a tie to break.</li>
 * </ul>
 *
 * <p>With no calibration in force this degrades to exactly the previous
 * behaviour — fixed threshold, best candidate wins — because a gateway that
 * has never been calibrated must keep working, and because changing the
 * serving rule for uncalibrated installs would be a behaviour change smuggled
 * in under a feature.
 *
 * <p>The calibration passed in is the one in force for the request's client
 * (v4 D.3, ADR 0019); the verdict records which scope it came from.
 */
final class CachePredictionSet {

  private CachePredictionSet() {
  }

  static CacheVerdict decide(List<Document> candidates, CalibrationState calibration) {
    double threshold = calibration.effectiveThreshold();
    CalibrationScope scope = calibration.scope();

    List<Document> set = candidates == null ? List.of() : candidates.stream()
        .filter(candidate -> admits(candidate, threshold))
        .toList();

    if (!calibration.isApplied()) {
      ConformalStatus status = calibration.status() == CalibrationStatus.STALE
          ? ConformalStatus.STALE_CALIBRATION : ConformalStatus.NOT_CALIBRATED;
      return new CacheVerdict(set.isEmpty() ? null : set.getFirst(), threshold, status,
          null, scope);
    }

    return switch (set.size()) {
      case 0 -> new CacheVerdict(null, threshold, ConformalStatus.EMPTY_SET, null, scope);
      case 1 -> new CacheVerdict(set.getFirst(), threshold, ConformalStatus.SINGLETON,
          null, scope);
      default -> new CacheVerdict(null, threshold, ConformalStatus.AMBIGUOUS, null, scope);
    };
  }

  /** An unscored candidate is not admitted: absence of a score is not a match. */
  private static boolean admits(Document candidate, double threshold) {
    Double score = candidate.getScore();
    return score != null && score >= threshold;
  }

  /**
   * What the cache decided, and under which threshold.
   *
   * @param hit         the entry to serve, or null
   * @param threshold   the acceptance threshold in force, calibrated or fixed
   * @param status      the shape of the prediction set, for the trace
   * @param reason      why this was not a plain lookup, or null
   * @param calibration which calibration set the threshold (ADR 0019)
   */
  record CacheVerdict(Document hit, double threshold, ConformalStatus status,
                      CacheDecisionReason reason, CalibrationScope calibration) {

    /** The same decision, not served: the request's own limits refused the hit. */
    CacheVerdict refused(CacheDecisionReason why) {
      return new CacheVerdict(null, threshold, status, why, calibration);
    }

    /** The same decision, with why the lookup was not a plain one. */
    CacheVerdict because(CacheDecisionReason why) {
      return new CacheVerdict(hit, threshold, status, why, calibration);
    }
  }
}
