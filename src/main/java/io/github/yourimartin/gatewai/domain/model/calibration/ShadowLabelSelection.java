package io.github.yourimartin.gatewai.domain.model.calibration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeVerdict;

/**
 * Which shadow labels a cache calibration may be fitted on (v4 D.3, ADR 0019,
 * points 3 and 4), and why each other one was left out.
 *
 * <p>A label counts when it was judged {@code YES} or {@code NO}, under the
 * current rubric, by the chosen judge, on a similarity from the current embedding
 * model, recorded under a threshold at or below the floor {@code F}, and scoring
 * at or above it. The last two make every counted score a draw from one
 * population — the would-hits above {@code F} — which is what lets the conformal
 * guarantee carry over (ADR 0019, Consequences).
 *
 * @param negatives  the similarities of the counted {@code NO} pairs
 * @param positives  the similarities of the counted {@code YES} pairs
 * @param exclusions what was read and what was left out
 * @param labelsFrom the oldest counted label, null when none counted
 * @param labelsTo   the newest counted label, null when none counted
 * @param perClient  the read and counted labels, client by client
 */
public record ShadowLabelSelection(
    List<Double> negatives,
    List<Double> positives,
    FitSummary.Exclusions exclusions,
    Instant labelsFrom,
    Instant labelsTo,
    SortedMap<String, FitSummary.ClientCounts> perClient
) {

  public ShadowLabelSelection {
    negatives = List.copyOf(negatives);
    positives = List.copyOf(positives);
    Objects.requireNonNull(exclusions, "exclusions");
    perClient = new TreeMap<>(perClient);
  }

  /** A copy of the per-client counts, so the record stays immutable. */
  @Override
  public SortedMap<String, FitSummary.ClientCounts> perClient() {
    return new TreeMap<>(perClient);
  }

  /** What one fit is allowed to read (ADR 0019, point 3). */
  public record Criteria(String judgeModel, String rubricVersion, String embeddingModel,
                         double floor) {

    public Criteria {
      Objects.requireNonNull(judgeModel, "judgeModel");
      Objects.requireNonNull(rubricVersion, "rubricVersion");
      Objects.requireNonNull(embeddingModel, "embeddingModel");
    }
  }

  /**
   * Applies the rules, in order: a label left out is counted under the first rule
   * it fails, so the reasons add up.
   *
   * @param labels     the would-hits that went to the judge
   * @param notSampled the would-hits that did not, counted beside them
   * @param criteria   the judge, rubric, embedding model and floor of this fit
   */
  public static ShadowLabelSelection select(List<ShadowLabel> labels, long notSampled,
                                            Criteria criteria) {
    List<Double> negatives = new ArrayList<>();
    List<Double> positives = new ArrayList<>();
    Tally tally = new Tally();
    SortedMap<String, int[]> clients = new TreeMap<>();
    Instant from = null;
    Instant to = null;

    for (ShadowLabel label : labels) {
      int[] client = clients.computeIfAbsent(
          Objects.toString(label.clientId(), ""), id -> new int[3]);
      client[0]++;
      if (!tally.counts(label, criteria)) {
        continue;
      }
      if (label.verdict() == JudgeVerdict.NO) {
        negatives.add(label.similarity());
        client[2]++;
      } else {
        positives.add(label.similarity());
        client[1]++;
      }
      Instant at = label.createdAt();
      if (at != null) {
        from = from == null || at.isBefore(from) ? at : from;
        to = to == null || at.isAfter(to) ? at : to;
      }
    }

    SortedMap<String, FitSummary.ClientCounts> perClient = new TreeMap<>();
    clients.forEach((id, counts) ->
        perClient.put(id, new FitSummary.ClientCounts(counts[0], counts[1], counts[2])));
    return new ShadowLabelSelection(negatives, positives,
        tally.exclusions(notSampled, labels.size()), from, to, perClient);
  }

  /** The exclusion counters, one per rule. */
  private static final class Tally {

    private int failed;
    private int unsure;
    private int noDecision;
    private int otherRubric;
    private int otherJudge;
    private int otherEmbedding;
    private int recordedAboveFloor;
    private int belowFloor;

    /** Whether {@code label} counts; when it does not, records the first reason. */
    boolean counts(ShadowLabel label, Criteria criteria) {
      if (label.judgeOutcome() != JudgeOutcome.JUDGED) {
        failed++;
      } else if (label.verdict() != JudgeVerdict.YES && label.verdict() != JudgeVerdict.NO) {
        unsure++;
      } else if (!label.hasDecision()) {
        noDecision++;
      } else if (!criteria.rubricVersion().equals(label.rubricVersion())) {
        otherRubric++;
      } else if (!criteria.judgeModel().equals(label.judgeModel())) {
        otherJudge++;
      } else if (!criteria.embeddingModel().equals(label.embeddingModel())) {
        otherEmbedding++;
      } else if (label.recordedThreshold() > criteria.floor()) {
        recordedAboveFloor++;
      } else if (label.similarity() < criteria.floor()) {
        belowFloor++;
      } else {
        return true;
      }
      return false;
    }

    FitSummary.Exclusions exclusions(long notSampled, int read) {
      return new FitSummary.Exclusions(notSampled, read, failed, unsure, noDecision,
          otherRubric, otherJudge, otherEmbedding, recordedAboveFloor, belowFloor);
    }
  }
}
