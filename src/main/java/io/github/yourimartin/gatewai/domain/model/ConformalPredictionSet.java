package io.github.yourimartin.gatewai.domain.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The tiers a routing decision could defensibly have taken, best first
 * (v2 batch 3, made to act in v2 batch 4).
 *
 * <p>Built from the route scores the classifier already reported, never
 * recomputed, so the set is by construction the one the decision was taken
 * from. Batch 3 recorded it as evidence; batch 4 lets it decide whether the
 * cascade escalates.
 *
 * @param tiers  distinct tiers whose best route cleared the threshold in force,
 *               in ranking order — empty when none did
 * @param status the shape of the set, and whether a calibration produced it
 */
public record ConformalPredictionSet(List<ModelTier> tiers,
                                     ConformalStatus status) {

  public ConformalPredictionSet {
    tiers = tiers == null ? List.of() : List.copyOf(tiers);
  }

  /**
   * The set implied by {@code candidates} under {@code calibration}.
   *
   * <p>The tiers are always computed, calibrated or not — a set built on the
   * fixed threshold is still the best description of what the router was
   * looking at. What the calibration changes is the {@link #status}: only a
   * {@code SINGLETON} / {@code EMPTY_SET} / {@code AMBIGUOUS} carries the α
   * guarantee, and the other two values say why one does not.
   */
  public static ConformalPredictionSet of(
      List<ClassificationJustification.RouteCandidate> candidates,
      CalibrationState calibration) {

    double threshold = calibration.effectiveThreshold();
    Set<ModelTier> tiers = new LinkedHashSet<>();
    if (candidates != null) {
      for (ClassificationJustification.RouteCandidate candidate : candidates) {
        if (candidate.tier() != null && candidate.score() >= threshold) {
          tiers.add(candidate.tier());
        }
      }
    }

    return new ConformalPredictionSet(List.copyOf(tiers),
        statusOf(tiers.size(), calibration));
  }

  private static ConformalStatus statusOf(int size, CalibrationState calibration) {
    if (!calibration.isApplied()) {
      return calibration.status() == CalibrationStatus.STALE
          ? ConformalStatus.STALE_CALIBRATION : ConformalStatus.NOT_CALIBRATED;
    }
    return switch (size) {
      case 0 -> ConformalStatus.EMPTY_SET;
      case 1 -> ConformalStatus.SINGLETON;
      default -> ConformalStatus.AMBIGUOUS;
    };
  }

  /**
   * Whether this set leaves the decision open enough to be worth paying for a
   * more expensive classifier (v2 batch 4).
   *
   * <ul>
   *   <li><b>empty</b> — no route is credible at all, so the alternative is the
   *       keyword heuristic. Escalate;</li>
   *   <li><b>one tier</b> — the routes agree on where this goes. Decided;</li>
   *   <li><b>several tiers</b> — escalate only when the top two routes are
   *       within {@code marginBand} of each other. Measured on the labelled set,
   *       a set of several tiers is the common case (70 of 100 prompts at
   *       α = 0.10), so the set alone would escalate almost everything; the
   *       margin is what separates a genuine tie from a clear winner among
   *       several credible routes.</li>
   * </ul>
   *
   * <p>The rule is deliberately the same whether a calibration is in force or
   * not — the calibration moves the threshold the set was built on, not the way
   * the set is read — so an uncalibrated gateway cascades on the fixed band and
   * records {@code NOT_CALIBRATED} beside the decision.
   *
   * @param margin     the winning route's score minus the runner-up's
   * @param marginBand how close the runner-up has to be to count as a tie
   */
  public boolean escalates(double margin, double marginBand) {
    return switch (tiers.size()) {
      case 0 -> true;
      case 1 -> false;
      default -> margin < marginBand;
    };
  }
}
