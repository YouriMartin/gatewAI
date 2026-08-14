package io.github.yourimartin.gatewai.domain.port.in;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.DecisionExplanation;
import io.github.yourimartin.gatewai.domain.model.TracedDecision;

/**
 * Answers "why did this request go there?" (v2 batch 9).
 *
 * <p>The two halves of the answer have different lifetimes, which is why they
 * are different methods rather than one flag:
 *
 * <ul>
 *   <li>{@link #find} and {@link #recent} read the <b>trace</b> — what happened,
 *       under the rules in force at the time. No recomputation, so a decision
 *       taken before a route edit keeps being explained by the rules it was
 *       taken under.</li>
 *   <li>{@link #explain} adds the <b>analysis</b> — attribution and
 *       counterfactuals — which is recomputed from a prompt, because only
 *       hashes are stored.</li>
 * </ul>
 */
public interface DecisionExplanationUseCase {

  /** One request's persisted decisions, or empty if none were recorded. */
  Optional<TracedDecision> find(String correlationId);

  /** The most recent requests' decisions, newest first. */
  List<TracedDecision> recent(int limit);

  /**
   * The trace of a past request plus what can still be said about it.
   *
   * <p>Attribution and counterfactuals come back as
   * {@code PROMPT_UNAVAILABLE}: they need to re-embed the text, and the text was
   * never stored. The route scores the decision was actually taken with are in
   * the trace's own justification, which is the part that does survive.
   *
   * @return empty when no decision was recorded under that correlation id
   */
  Optional<DecisionExplanation> explain(String correlationId);

  /**
   * A full analysis of a prompt against the rules in force <b>now</b>: what it
   * would match, which segments carry that match, and which outcomes it just
   * missed.
   *
   * <p>No decision is attached, because none was taken — this is the "what
   * would happen if" question, not the "what happened" one, and conflating them
   * is how an explanation ends up describing a request that never existed.
   */
  DecisionExplanation explainPrompt(String prompt);
}
