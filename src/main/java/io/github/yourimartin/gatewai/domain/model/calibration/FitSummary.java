package io.github.yourimartin.gatewai.domain.model.calibration;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a calibration was fitted on, kept on its row (v4 D.3, ADR 0019). It
 * outlives the labels: shadow labels are purged with the decision tables, and the
 * summary is what still says what a threshold rests on.
 */
public sealed interface FitSummary {

  /**
   * A fit on the hand-labelled cases.
   *
   * @param source the resource the cases were read from
   * @param read   the cases read
   * @param fitted the cases the quantile was computed on: every scored prompt for
   *               routing, the non-servable pairs for the cache
   */
  record LabelledSet(String source, int read, int fitted) implements FitSummary {
  }

  /**
   * A fit on the judge's verdicts on shadow would-hits.
   *
   * @param exclusions                the labels read, and why each one that did not
   *                                  count was left out
   * @param yes                       counted pairs the judge found servable
   * @param no                        counted pairs it did not: the sample
   *                                  {@code q̂} is fitted on
   * @param labelsFrom                the oldest counted label, null when none
   * @param labelsTo                  the newest counted label, null when none
   * @param refusedServableShare      of the counted {@code YES} pairs, the share
   *                                  the new threshold would refuse; null when
   *                                  there is none
   * @param ambiguousBecomingServable decisions ambiguous at the floor that the new
   *                                  threshold would serve, though no label speaks
   *                                  for them
   * @param modelledRateP90           with probability 0.9, this fit admits at most
   *                                  this share of the judge's {@code NO} pairs —
   *                                  modelled from the sample size
   *                                  ({@link ConformalSpread}), not measured
   * @param minPairs                  the judged-pair minimum the fit was held to
   * @param minNegatives              the {@code NO} minimum it was held to
   * @param perClient                 the same counts, client by client
   */
  record Shadow(
      Exclusions exclusions,
      int yes,
      int no,
      Instant labelsFrom,
      Instant labelsTo,
      Double refusedServableShare,
      long ambiguousBecomingServable,
      double modelledRateP90,
      int minPairs,
      int minNegatives,
      SortedMap<String, ClientCounts> perClient
  ) implements FitSummary {

    public Shadow {
      Objects.requireNonNull(exclusions, "exclusions");
      perClient = perClient == null
          ? new TreeMap<>() : new TreeMap<>(Map.copyOf(perClient));
    }

    /** A copy of the per-client counts, so the record stays immutable. */
    @Override
    public SortedMap<String, ClientCounts> perClient() {
      return new TreeMap<>(perClient);
    }
  }

  /**
   * Every label read, and the first rule of ADR 0019 each excluded one failed. A
   * label is counted under one reason only, so the reasons add up to
   * {@code read − counted}.
   *
   * @param notSampled         would-hits the judge never saw (not read as labels)
   * @param read               labels read: would-hits that went to the judge
   * @param failed             the judge call failed
   * @param unsure             judged {@code UNSURE}
   * @param noDecision         the cache decision it grades is gone
   * @param otherRubric        judged under another rubric
   * @param otherJudge         judged by another model
   * @param otherEmbedding     its similarity came from another embedding model
   * @param recordedAboveFloor recorded under a threshold above the floor
   * @param belowFloor         its similarity is below the floor
   */
  record Exclusions(long notSampled, int read, int failed, int unsure, int noDecision,
                    int otherRubric, int otherJudge, int otherEmbedding,
                    int recordedAboveFloor, int belowFloor) {

    /** How many labels were read and not counted. */
    public int excluded() {
      return failed + unsure + noDecision + otherRubric + otherJudge + otherEmbedding
          + recordedAboveFloor + belowFloor;
    }
  }

  /**
   * One client's share of a shadow fit.
   *
   * @param read the client's labels read
   * @param yes  its counted {@code YES} pairs
   * @param no   its counted {@code NO} pairs
   */
  record ClientCounts(int read, int yes, int no) {
  }
}
