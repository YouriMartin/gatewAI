package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.CascadeLevel;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ConformalPredictionSet;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * V4 classifier: the three strategies chained by increasing cost, each level
 * reached only when the previous one could not decide with enough confidence
 * (v2 batch 4).
 *
 * <ol>
 *   <li><b>Deterministic signals</b> — code fence, over-long prompt. Free, and
 *       it answers before anything is embedded;</li>
 *   <li><b>Semantic routes</b> — one local embedding call, already paid for by
 *       the cache on the same request (batch 0.2 memoizes it);</li>
 *   <li><b>The classifier model</b> — reached only when level 2's conformal
 *       prediction set leaves the tier open.</li>
 * </ol>
 *
 * <p>The three classifiers are reused <b>unchanged</b>: this class owns the
 * gates, not the classification. What it adds to the trace is
 * {@link CascadeLevel} — how far a decision had to go, which is what it cost.
 *
 * <p>The escalation gate is {@link ConformalPredictionSet#escalates}: an empty
 * set escalates, a singleton decides, and several tiers escalate only when the
 * top two routes are within {@code cascade-margin-band}. The band exists
 * because the set alone is not discriminating enough — on the labelled set, 70
 * of 100 prompts produce a set of several tiers at α = 0.10, so escalating on
 * the set alone would call the model for most requests and the cascade would be
 * a cost, not a saving.
 */
@Component
class CascadeComplexityClassifier implements ComplexityClassifier {

  private static final Logger LOG =
      LoggerFactory.getLogger(CascadeComplexityClassifier.class);

  private static final String LEVEL_METRIC = "gatewai.classifier.cascade.level";

  private final ClassifierProperties properties;
  private final HeuristicComplexityClassifier heuristic;
  private final EmbeddingComplexityClassifier embedding;
  private final LlmComplexityClassifier llm;
  private final CalibrationUseCase calibrations;

  /**
   * One counter per level, registered up front: the escalation rate is a ratio,
   * and a level that has never fired has to read as zero rather than be missing
   * from the scrape.
   */
  private final Map<CascadeLevel, Counter> levelCounters =
      new EnumMap<>(CascadeLevel.class);

  CascadeComplexityClassifier(ClassifierProperties properties,
                              HeuristicComplexityClassifier heuristic,
                              EmbeddingComplexityClassifier embedding,
                              LlmComplexityClassifier llm,
                              CalibrationUseCase calibrations,
                              MeterRegistry registry) {
    this.properties = properties;
    this.heuristic = heuristic;
    this.embedding = embedding;
    this.llm = llm;
    this.calibrations = calibrations;

    for (CascadeLevel level : CascadeLevel.values()) {
      levelCounters.put(level, Counter.builder(LEVEL_METRIC)
          .tag("level", level.name().toLowerCase(Locale.ROOT))
          .description("Classifications by the deepest cascade level reached")
          .register(registry));
    }
  }

  @Override
  public ClassificationOutcome classify(String userText) {
    double marginBand = properties.getCascadeMarginBand();

    Optional<ClassificationOutcome> deterministic =
        heuristic.deterministicSignal(userText);
    if (deterministic.isPresent()) {
      return atLevel(CascadeLevel.DETERMINISTIC, marginBand,
          deterministic.get(), null);
    }

    ClassificationOutcome routed = embedding.classify(userText);
    ClassificationJustification.Embedding evidence =
        routeScores(routed.justification());
    if (evidence == null) {
      // The embedding level produced no scores at all (no routes configured, or
      // the model was unreachable) and has already handed over to the heuristic.
      // Escalating on that would mean paying a model call for an outage.
      return atLevel(CascadeLevel.EMBEDDING, marginBand, routed, null);
    }

    ConformalPredictionSet set = ConformalPredictionSet.of(
        evidence.candidates(), calibrations.state(CalibrationTarget.ROUTING));
    if (!set.escalates(evidence.margin(), marginBand)) {
      return atLevel(CascadeLevel.EMBEDDING, marginBand, routed, null);
    }

    LOG.debug("Escalating to the classifier model: set={} ({}), margin={} < {}",
        set.tiers(), set.status(), evidence.margin(), marginBand);
    return atLevel(CascadeLevel.LLM, marginBand, llm.classify(userText),
        evidence);
  }

  /**
   * Wraps the deciding level's outcome, counts the level, and keeps the inner
   * justification verbatim — a fallback reached through the cascade must stay
   * recognisable as a fallback.
   */
  private ClassificationOutcome atLevel(
      CascadeLevel level, double marginBand, ClassificationOutcome decided,
      ClassificationJustification escalatedOn) {

    levelCounters.get(level).increment();
    return new ClassificationOutcome(decided.tier(),
        new ClassificationJustification.Cascade(level, marginBand,
            decided.justification(), escalatedOn));
  }

  /**
   * The route scores behind level 2's answer, whether it decided on them or
   * handed over below the threshold — the hand-over carries them as evidence,
   * and that is precisely the case worth escalating.
   */
  private static ClassificationJustification.Embedding routeScores(
      ClassificationJustification justification) {
    return switch (justification) {
      case ClassificationJustification.Embedding scores -> scores;
      case ClassificationJustification.Fallback fallback ->
          fallback.evidence() instanceof ClassificationJustification.Embedding scores
              ? scores : null;
      default -> null;
    };
  }
}
