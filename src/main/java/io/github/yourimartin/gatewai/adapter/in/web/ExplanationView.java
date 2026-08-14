package io.github.yourimartin.gatewai.adapter.in.web;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.AttributionReport;
import io.github.yourimartin.gatewai.domain.model.Counterfactual;
import io.github.yourimartin.gatewai.domain.model.CounterfactualReport;
import io.github.yourimartin.gatewai.domain.model.DecisionExplanation;
import io.github.yourimartin.gatewai.domain.model.ExplanationProvenance;
import io.github.yourimartin.gatewai.domain.model.SegmentAttribution;
import io.github.yourimartin.gatewai.domain.model.TracedDecision;

/**
 * The full "why this decision" answer (v2 batch 9).
 *
 * <p>Four sections that are deliberately not blended into one story, because
 * they are true at different times: {@code decision} is what happened,
 * {@code attribution} and {@code counterfactuals} are computed now,
 * {@code carbon} points at a record that lives elsewhere, and
 * {@code provenance} is what says whether the first three describe the same
 * world.
 *
 * @param decision        the persisted trace, null for an on-the-fly prompt
 * @param attribution     which segments carried the match — always present,
 *                        with a status when it could not be computed
 * @param counterfactuals which outcomes were missed, same contract
 * @param carbon          a <b>reference</b> to the carbon record by correlation
 *                        id, never a copy of it: the numbers live in
 *                        {@code request_log} and duplicating them here is how
 *                        two sources of truth start disagreeing
 * @param provenance      never null — every number above is relative to it
 */
record ExplanationView(DecisionView decision, Attribution attribution,
                       Counterfactuals counterfactuals, CarbonRef carbon,
                       Provenance provenance) {

  static ExplanationView of(DecisionExplanation explanation) {
    TracedDecision decision = explanation.decision();
    return new ExplanationView(
        decision == null ? null : DecisionView.of(decision),
        Attribution.of(explanation.attribution()),
        Counterfactuals.of(explanation.counterfactuals()),
        CarbonRef.of(decision),
        Provenance.of(explanation.provenance()));
  }

  /**
   * Which parts of the prompt carried the match (batch 7).
   *
   * @param status           COMPUTED, or why not — {@code PROMPT_UNAVAILABLE}
   *                         for a past decision, since only hashes are stored
   * @param route            the matched route
   * @param tier             the tier it maps to
   * @param matchedUtterance the route example the prompt was closest to
   * @param similarity       the similarity being decomposed
   * @param segments         strongest first
   */
  record Attribution(String status, String route, String tier,
                     String matchedUtterance, double similarity,
                     List<Segment> segments) {

    static Attribution of(AttributionReport report) {
      return new Attribution(report.status().name(), report.route(),
          report.tier() == null ? null : report.tier().name(),
          report.matchedUtterance(), report.similarity(),
          report.segments().stream().map(Segment::of).toList());
    }
  }

  /**
   * @param segment      the text
   * @param contribution what removing it costs the similarity. Negative is
   *                     meaningful: that segment pulled the other way
   * @param share        its part of the total positive contribution — a
   *                     normalization, not a probability
   * @param rank         1-based, strongest first
   */
  record Segment(String segment, double contribution, double share, int rank) {

    static Segment of(SegmentAttribution attribution) {
      return new Segment(attribution.segment(), attribution.contribution(),
          attribution.share(), attribution.rank());
    }
  }

  /**
   * Where the request would have gone instead (batch 8).
   *
   * @param status           COMPUTED, or why not
   * @param chosenRoute      the route that won
   * @param chosenTier       the tier it maps to
   * @param chosenUtterance  the example it matched
   * @param chosenSimilarity the reference every delta is measured from
   * @param alternatives     the near misses, closest first
   */
  record Counterfactuals(String status, String chosenRoute, String chosenTier,
                         String chosenUtterance, double chosenSimilarity,
                         List<Alternative> alternatives) {

    static Counterfactuals of(CounterfactualReport report) {
      return new Counterfactuals(report.status().name(), report.chosenRoute(),
          report.chosenTier() == null ? null : report.chosenTier().name(),
          report.chosenUtterance(), report.chosenSimilarity(),
          report.alternatives().stream().map(Alternative::of).toList());
    }
  }

  /**
   * @param tier             the outcome that was missed
   * @param route            the route that would have produced it
   * @param nearestUtterance a <b>configured</b> example, never user data
   * @param similarity       how close it came
   * @param delta            chosen similarity minus this one; small means the
   *                         decision nearly went the other way
   * @param rank             1-based, closest first
   */
  record Alternative(String tier, String route, String nearestUtterance,
                     double similarity, double delta, int rank) {

    static Alternative of(Counterfactual counterfactual) {
      return new Alternative(counterfactual.tier().name(),
          counterfactual.route(), counterfactual.nearestUtterance(),
          counterfactual.similarity(), counterfactual.gap(),
          counterfactual.rank());
    }
  }

  /** A pointer to the carbon record of the same request, by correlation id. */
  record CarbonRef(String correlationId) {

    static CarbonRef of(TracedDecision decision) {
      return decision == null || decision.correlationId() == null
          ? null : new CarbonRef(decision.correlationId());
    }
  }

  /**
   * @param embeddingModelVersion the model behind the vectors
   * @param routingConfigVersion  hash of the routing rules
   * @param calibrationDate       when the governing calibration was fitted
   * @param status                VALID, STALE, ABSENT or DISABLED — a decision
   *                              taken under a stale calibration and one taken
   *                              before any calibration existed are both
   *                              degraded, and differently
   */
  record Provenance(String embeddingModelVersion, String routingConfigVersion,
                    Instant calibrationDate, String status) {

    static Provenance of(ExplanationProvenance provenance) {
      return new Provenance(provenance.embeddingModel(),
          provenance.routingConfigVersion(), provenance.calibrationDate(),
          provenance.calibrationStatus() == null
              ? null : provenance.calibrationStatus().name());
    }
  }
}
