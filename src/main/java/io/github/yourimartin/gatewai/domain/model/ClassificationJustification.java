package io.github.yourimartin.gatewai.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * Why a request was classified into a tier (v2 batch 1).
 *
 * <p>Every strategy already computes this — the matched keyword, the route
 * scores, the model's stated reasoning — and used to throw it away. Capturing it
 * costs nothing on the nominal path and is what makes a routing decision
 * explainable and, from batch 2, replayable.
 *
 * <p>Sealed so that consumers (JSONB mapping, the explain API, the dashboard)
 * switch exhaustively: a new strategy cannot be added without every reader being
 * forced to say what it renders.
 */
public sealed interface ClassificationJustification {

  /**
   * The strategy that actually decided the tier. For {@link Fallback} this is
   * the strategy that took over, <b>not</b> the one that was configured — that
   * one is {@link Fallback#fallbackFrom()}.
   */
  ClassificationStrategy strategy();

  /**
   * The route scores behind a justification, wherever they ended up sitting
   * (v2 batch 6).
   *
   * <p>The embedding level reports them four different ways depending on what
   * happened to its answer — it decided ({@link Embedding}), it handed over
   * below threshold ({@link Fallback#evidence()}), the cascade kept its answer
   * ({@link Cascade#decided()}) or the cascade escalated past it
   * ({@link Cascade#escalatedOn()}). Every reader that wants the margin or the
   * candidates needs all four cases, and by batch 6 four copies of that
   * {@code switch} existed. This is the one, on the sealed type itself, so a new
   * variant cannot quietly go unhandled.
   *
   * @return the scores, or empty when no embedding ran at all
   */
  static Optional<Embedding> routeScores(
      ClassificationJustification justification) {
    return switch (justification) {
      case null -> Optional.empty();
      case Embedding embedding -> Optional.of(embedding);
      case Fallback fallback -> routeScores(fallback.evidence());
      case Cascade cascade -> routeScores(cascade.escalatedOn())
          .or(() -> routeScores(cascade.decided()));
      default -> Optional.empty();
    };
  }

  /** Which heuristic rule fired, in the order the classifier evaluates them. */
  enum HeuristicRule {
    /** Blank or null input: nothing to classify, cheapest tier. */
    BLANK_TEXT,
    /** The text contains a code fence. */
    CODE_FENCE,
    /** The text contains one of the configured premium keywords. */
    PREMIUM_KEYWORD,
    /** The text is longer than the premium length threshold. */
    PREMIUM_LENGTH,
    /** The text is longer than the entry length threshold. */
    ENTRY_LENGTH,
    /** No rule fired: the default (cheapest) tier. */
    DEFAULT
  }

  /** Why a smarter strategy handed over to a cheaper one. */
  enum FallbackCause {
    /** Embedding strategy selected but no usable route is configured. */
    NO_ROUTES_CONFIGURED,
    /** No route example reached the similarity threshold. */
    BELOW_THRESHOLD,
    /** The embedding call failed (model unreachable, bad response). */
    EMBEDDING_ERROR,
    /** The classifier model answered without a usable tier. */
    NO_TIER_RETURNED,
    /** The classifier model call failed. */
    LLM_ERROR
  }

  /**
   * Heuristic decision: the rule that fired and what it observed.
   *
   * @param rule           the rule that decided
   * @param matchedKeyword the premium keyword found, only for
   *                       {@link HeuristicRule#PREMIUM_KEYWORD}
   * @param observedLength the text length, only for the length rules
   * @param threshold      the length threshold it was compared against
   */
  record Heuristic(HeuristicRule rule, String matchedKeyword,
                   Integer observedLength, Integer threshold)
      implements ClassificationJustification {

    @Override
    public ClassificationStrategy strategy() {
      return ClassificationStrategy.HEURISTIC;
    }

    public static Heuristic of(HeuristicRule rule) {
      return new Heuristic(rule, null, null, null);
    }

    public static Heuristic keyword(String matchedKeyword) {
      return new Heuristic(HeuristicRule.PREMIUM_KEYWORD, matchedKeyword,
          null, null);
    }

    public static Heuristic length(HeuristicRule rule, int observedLength,
                                   int threshold) {
      return new Heuristic(rule, null, observedLength, threshold);
    }
  }

  /**
   * Embedding decision: what every route scored, and by how much the winner won.
   *
   * @param candidates ranked routes, best first — one entry per route, each
   *                   carrying the closest example that route offered
   * @param topScore   the winning similarity
   * @param margin     {@code topScore} minus the runner-up's score, 0 when there
   *                   is only one route. A better confidence signal than the raw
   *                   score, and what batch 3 calibrates
   * @param threshold  the similarity threshold in force at decision time
   */
  record Embedding(List<RouteCandidate> candidates, double topScore,
                   double margin, double threshold)
      implements ClassificationJustification {

    public Embedding {
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    @Override
    public ClassificationStrategy strategy() {
      return ClassificationStrategy.EMBEDDING;
    }
  }

  /**
   * One route's best showing against the request.
   *
   * @param route         the route name
   * @param tier          the tier that route maps to
   * @param bestUtterance the route's example closest to the request — free to
   *                      capture, since selection is already max-over-utterances
   * @param score         cosine similarity of that example
   * @param rank          1-based position among routes, best first
   */
  record RouteCandidate(String route, ModelTier tier, String bestUtterance,
                        double score, int rank) {
  }

  /**
   * LLM decision: the model's own stated reason, and which model said it.
   *
   * @param reasoning         the structured output's reasoning field
   * @param classifierModelId the model that produced it
   */
  record Llm(String reasoning, String classifierModelId)
      implements ClassificationJustification {

    @Override
    public ClassificationStrategy strategy() {
      return ClassificationStrategy.LLM;
    }
  }

  /**
   * A smarter strategy handed over to a cheaper one, which then decided.
   *
   * <p>This is the variant that makes a degraded decision distinguishable from a
   * nominal one: the same tier reached by the heuristic means something quite
   * different when it was reached because Ollama was unreachable.
   *
   * @param fallbackFrom the configured strategy that stepped aside
   * @param cause        why it stepped aside
   * @param effective    the justification of the strategy that actually decided
   * @param evidence     what the strategy that stepped aside had computed
   *                     before giving up — the route scores behind a
   *                     {@link FallbackCause#BELOW_THRESHOLD}, for instance.
   *                     Null when it produced nothing (an error, no routes).
   *                     Knowing the best route only reached 0.41 is exactly
   *                     what explains the hand-over, and is what batch 3
   *                     calibrates
   */
  record Fallback(ClassificationStrategy fallbackFrom, FallbackCause cause,
                  ClassificationJustification effective,
                  ClassificationJustification evidence)
      implements ClassificationJustification {

    public Fallback {
      if (effective == null) {
        throw new IllegalArgumentException(
            "effective justification is required; use FailSafe when no "
                + "strategy decided");
      }
    }

    public Fallback(ClassificationStrategy fallbackFrom, FallbackCause cause,
                    ClassificationJustification effective) {
      this(fallbackFrom, cause, effective, null);
    }

    /** The strategy that decided — never {@link #fallbackFrom()}. */
    @Override
    public ClassificationStrategy strategy() {
      return effective.strategy();
    }
  }

  /**
   * A cascade decision (v2 batch 4): how far it had to go, and what decided
   * there.
   *
   * <p>Wrapping rather than flattening keeps two questions separable — which
   * classifier produced the tier ({@link #strategy()}, unchanged for every
   * reader that already understood the three strategies) and what the cascade
   * paid to get it ({@link #level()}). The evidence that triggered an escalation
   * rides along for the same reason a below-threshold hand-over carries the
   * route scores: "the top two routes were 0.011 apart, inside a 0.02 band" is
   * the whole explanation of why a model call was worth making.
   *
   * @param level       the deepest level reached, which is what the decision cost
   * @param marginBand  the ambiguity band in force at decision time
   * @param decided     the justification of the level that produced the tier
   * @param escalatedOn what the embedding level had computed when it handed over,
   *                    null when it was not reached or did not escalate
   */
  record Cascade(CascadeLevel level, double marginBand,
                 ClassificationJustification decided,
                 ClassificationJustification escalatedOn)
      implements ClassificationJustification {

    public Cascade {
      if (level == null) {
        throw new IllegalArgumentException("level is required");
      }
      if (decided == null) {
        throw new IllegalArgumentException("decided justification is required");
      }
    }

    /** The strategy that decided at {@link #level()} — never {@code CASCADE}. */
    @Override
    public ClassificationStrategy strategy() {
      return decided.strategy();
    }
  }

  /**
   * No strategy decided: the request was routed to the premium tier defensively.
   *
   * <p>Happens when the LLM classifier fails and {@code fallback-to-heuristic}
   * is off — the gateway then favours answer quality over cost, and the trace
   * must say that this was a fail-safe rather than a judgement.
   *
   * @param fallbackFrom the strategy that failed
   * @param cause        why it failed
   */
  record FailSafe(ClassificationStrategy fallbackFrom, FallbackCause cause)
      implements ClassificationJustification {

    @Override
    public ClassificationStrategy strategy() {
      return fallbackFrom;
    }
  }
}
