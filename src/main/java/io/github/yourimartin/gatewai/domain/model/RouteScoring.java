package io.github.yourimartin.gatewai.domain.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Scores a request against semantic routes: <b>max over utterances</b>, one
 * candidate per route, best first.
 *
 * <p>Lives in the domain because two callers need the identical answer and must
 * not drift: the router, which decides with it, and the conformal calibration,
 * which fits a threshold on it (v2 batch 3). Having the calibration ask the
 * router would have been the other way to guarantee that, and it produced a bean
 * cycle — this is the version that does not.
 *
 * <p>Max-over-utterances rather than a centroid: a route whose examples are
 * internally diverse ("refactor this service", "prove this is O(n log n)") has
 * no meaningful centre, and the nearest single example is the honest signal.
 */
public final class RouteScoring {

  private RouteScoring() {
  }

  /**
   * Ranks every route against {@code query}, each carrying the closest example
   * it offered. One pass, so the scores that explain a decision cost nothing
   * beyond making it.
   *
   * @param query  the embedded request
   * @param routes the embedded routes; routes without examples are skipped
   * @return candidates sorted by descending score, ranked from 1
   */
  public static List<ClassificationJustification.RouteCandidate> rank(
      float[] query, List<EmbeddedRoute> routes) {

    List<ClassificationJustification.RouteCandidate> candidates = new ArrayList<>();
    for (EmbeddedRoute embedded : routes) {
      SemanticRoute route = embedded.route();
      double bestScore = Double.NEGATIVE_INFINITY;
      String bestUtterance = null;

      for (int i = 0; i < embedded.exampleVectors().size(); i++) {
        double similarity =
            Similarity.cosine(query, embedded.exampleVectors().get(i));
        if (similarity > bestScore) {
          bestScore = similarity;
          bestUtterance = route.examples().get(i);
        }
      }
      if (bestUtterance != null) {
        candidates.add(new ClassificationJustification.RouteCandidate(
            route.name(), route.tier(), bestUtterance, bestScore, 0));
      }
    }

    candidates.sort(Comparator.comparingDouble(
        ClassificationJustification.RouteCandidate::score).reversed());

    List<ClassificationJustification.RouteCandidate> ranked =
        new ArrayList<>(candidates.size());
    for (int i = 0; i < candidates.size(); i++) {
      ClassificationJustification.RouteCandidate candidate = candidates.get(i);
      ranked.add(new ClassificationJustification.RouteCandidate(
          candidate.route(), candidate.tier(), candidate.bestUtterance(),
          candidate.score(), i + 1));
    }
    return ranked;
  }

  /**
   * The best score among the routes mapped to {@code tier} — the quantity a
   * routing calibration measures non-conformity from.
   *
   * @return empty when no route carries that tier, which makes the case
   *         unusable rather than badly scored
   */
  public static OptionalDouble bestScoreFor(
      ModelTier tier, List<ClassificationJustification.RouteCandidate> candidates) {
    return candidates.stream()
        .filter(candidate -> candidate.tier() == tier)
        .mapToDouble(ClassificationJustification.RouteCandidate::score)
        .max();
  }
}
