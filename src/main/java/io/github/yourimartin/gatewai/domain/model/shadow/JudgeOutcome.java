package io.github.yourimartin.gatewai.domain.model.shadow;

/** Whether a would-hit went to the LLM judge, and how that ended (v4 D.2). */
public enum JudgeOutcome {

  /** Not in the sample (or the judge is off): compared by similarity only. */
  NOT_SAMPLED,

  /** Judged: the verdict is recorded. */
  JUDGED,

  /** Sampled, but the judge call failed: no verdict, and not counted as one. */
  FAILED
}
