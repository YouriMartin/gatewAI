package io.github.yourimartin.gatewai.domain.model;

/**
 * What a calibration's {@code α} actually promises (v2 batch 3).
 *
 * <p>The two decisions fail in ways that cost completely different things, so
 * they are calibrated on different sides of the problem. Naming that here —
 * rather than leaving one {@code alpha} field whose meaning depends on the
 * target — is what stops the number being read as the same promise twice.
 */
public enum ConformalGuarantee {

  /**
   * The prediction set contains the correct target at least {@code 1 − α} of
   * the time. Calibrated on the <b>positive</b> class: cases whose right answer
   * is known.
   *
   * <p>Used for routing, where the cost of missing the correct route is a
   * hand-over to the heuristic — measured in batch 5 as the dominant source of
   * misrouting.
   */
  CORRECT_TARGET_COVERAGE,

  /**
   * At most {@code α} of the cases that should <b>not</b> be served are served.
   * Calibrated on the <b>negative</b> class: pairs a human judged wrong.
   *
   * <p>Used for the cache, where the two errors are not symmetric. A false
   * negative costs one inference; a false positive returns another question's
   * answer to a user, with the confidence of a real one. Guaranteeing coverage
   * of the servable pairs would have controlled the cheap error and left the
   * expensive one free — the wrong way round.
   */
  WRONG_ANSWER_RATE
}
