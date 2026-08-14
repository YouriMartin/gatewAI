package io.github.yourimartin.gatewai.domain.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a route ranking backwards: which outcomes the request just missed
 * (v2 batch 8).
 *
 * <p>Pure arithmetic over candidates that were already scored — no embedding, no
 * search. The ranking is computed once by {@link RouteScoring}, whether for a
 * live decision or for an explanation, so counterfactuals cost nothing beyond
 * having it.
 *
 * <p>Two filters make the list worth reading, and both are deliberate:
 *
 * <ul>
 *   <li><b>The chosen tier is excluded.</b> A route the request nearly matched
 *       instead is only interesting when matching it would have changed
 *       something; "it would have gone to LOCAL" about a request that went to
 *       LOCAL is not a counterfactual.</li>
 *   <li><b>One route per tier.</b> The alternative being offered is an
 *       <em>outcome</em>, and three ways to reach the same tier crowd out the
 *       tiers the reader has not been told about. The best-scoring route for a
 *       tier is the one that would have won it, so it is the one kept.</li>
 * </ul>
 */
public final class Counterfactuals {

  private Counterfactuals() {
  }

  /**
   * The nearest alternative outcomes, closest first.
   *
   * @param ranked candidates sorted by descending score, as
   *               {@link RouteScoring#rank} returns them — the first is the
   *               route that won and the reference for every gap
   * @param limit  how many alternatives to keep; ≤ 0 keeps none
   * @return one entry per reachable other tier, up to {@code limit}, empty when
   *         every route leads to the tier that already won
   */
  public static List<Counterfactual> from(
      List<ClassificationJustification.RouteCandidate> ranked, int limit) {

    if (ranked == null || ranked.isEmpty() || limit <= 0) {
      return List.of();
    }

    ClassificationJustification.RouteCandidate chosen = ranked.getFirst();
    Set<ModelTier> seen = EnumSet.noneOf(ModelTier.class);
    if (chosen.tier() != null) {
      seen.add(chosen.tier());
    }

    List<Counterfactual> alternatives = new ArrayList<>(limit);
    for (ClassificationJustification.RouteCandidate candidate :
        ranked.subList(1, ranked.size())) {

      if (candidate.tier() == null || !seen.add(candidate.tier())) {
        continue;
      }
      alternatives.add(new Counterfactual(
          candidate.route(), candidate.tier(), candidate.bestUtterance(),
          candidate.score(), gap(chosen.score(), candidate.score()),
          alternatives.size() + 1));

      if (alternatives.size() == limit) {
        break;
      }
    }
    return List.copyOf(alternatives);
  }

  /**
   * Floored at zero: the candidates are sorted, so a negative gap would mean the
   * ranking lied, and reporting one would be reporting the bug as a finding.
   */
  private static double gap(double chosen, double candidate) {
    return Math.max(0, chosen - candidate);
  }
}
