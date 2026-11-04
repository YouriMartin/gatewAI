package io.github.yourimartin.gatewai.domain.model.shadow;

/**
 * The estimated wrong-hit rate of one client's would-hits, as one LLM judge saw
 * them (v4 D.2): {@code NO / (YES + NO)}, with its Wilson 95 % interval and the
 * size of the sample behind it. Never shown without its {@link #label()}: the
 * verdicts are a model's, not a person's.
 *
 * @param counts   what it is computed from
 * @param judged   YES + NO: the sample size of the rate. UNSURE is excluded
 *                 from it and counted in {@code counts}
 * @param rate     the point estimate, null when nothing was judged
 * @param interval its 95 % interval, null when nothing was judged
 * @param label    "judged by &lt;model&gt; (rubric &lt;version&gt;), not by a human",
 *                 or why there is no rate
 */
public record WrongHitEstimate(WouldHitCounts counts, long judged, Double rate,
                               WilsonInterval interval, String label) {

  /** The estimate {@code counts} supports, labelled. */
  public static WrongHitEstimate of(WouldHitCounts counts) {
    long judged = counts.yes() + counts.no();
    if (counts.judgeModel() == null) {
      return new WrongHitEstimate(counts, 0, null, null,
          "not judged: answer similarity only");
    }
    String label = "judged by " + counts.judgeModel() + " (rubric " + counts.rubricVersion()
        + "), not by a human";
    if (judged == 0) {
      return new WrongHitEstimate(counts, 0, null, null, label + "; no YES/NO verdict yet");
    }
    return new WrongHitEstimate(counts, judged, (double) counts.no() / judged,
        WilsonInterval.of(counts.no(), judged), label);
  }
}
