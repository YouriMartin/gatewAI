package io.github.yourimartin.gatewai.application.service;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.AttributionReport;
import io.github.yourimartin.gatewai.domain.model.AttributionStatus;
import io.github.yourimartin.gatewai.domain.model.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.CounterfactualReport;
import io.github.yourimartin.gatewai.domain.model.CounterfactualStatus;
import io.github.yourimartin.gatewai.domain.model.DecisionExplanation;
import io.github.yourimartin.gatewai.domain.model.ExplanationProvenance;
import io.github.yourimartin.gatewai.domain.model.RoutingConfigVersion;
import io.github.yourimartin.gatewai.domain.model.TracedDecision;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;
import io.github.yourimartin.gatewai.domain.port.in.DecisionExplanationUseCase;
import io.github.yourimartin.gatewai.domain.port.in.PromptAttributionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.RouteCounterfactualUseCase;
import io.github.yourimartin.gatewai.domain.port.out.DecisionHistory;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.springframework.stereotype.Service;

/**
 * Assembles the answer to "why did this request go there?" (v2 batch 9).
 *
 * <p>Composition, not new machinery: the trace comes from
 * {@link DecisionHistory}, the segment analysis from batch 7, the alternatives
 * from batch 8, the threshold provenance from batch 3. What this class owns is
 * the one thing none of them can know alone — <b>which questions are answerable
 * about which request</b>.
 *
 * <p>That distinction is load-bearing. Only prompt hashes are stored, so a past
 * decision cannot be re-embedded: explaining one returns the full trace and two
 * reports marked {@code PROMPT_UNAVAILABLE}. Explaining a prompt returns the
 * reverse — a full analysis and no decision, because none was taken. Returning
 * an empty segment list in either case would read as "nothing carried this
 * decision", which is a different and false statement.
 */
@Service
class DecisionExplanationService implements DecisionExplanationUseCase {

  private final DecisionHistory history;
  private final PromptAttributionUseCase attribution;
  private final RouteCounterfactualUseCase counterfactuals;
  private final CalibrationUseCase calibrations;
  private final RoutingConfigPort routingConfig;
  private final TextEmbedder embedder;

  DecisionExplanationService(DecisionHistory history,
                             PromptAttributionUseCase attribution,
                             RouteCounterfactualUseCase counterfactuals,
                             CalibrationUseCase calibrations,
                             RoutingConfigPort routingConfig,
                             TextEmbedder embedder) {
    this.history = history;
    this.attribution = attribution;
    this.counterfactuals = counterfactuals;
    this.calibrations = calibrations;
    this.routingConfig = routingConfig;
    this.embedder = embedder;
  }

  @Override
  public Optional<TracedDecision> find(String correlationId) {
    if (correlationId == null || correlationId.isBlank()) {
      return Optional.empty();
    }
    return history.byCorrelationId(correlationId);
  }

  @Override
  public List<TracedDecision> recent(int limit) {
    return limit <= 0 ? List.of() : history.recent(limit);
  }

  @Override
  public Optional<DecisionExplanation> explain(String correlationId) {
    return find(correlationId).map(decision -> new DecisionExplanation(
        decision,
        AttributionReport.notComputed(AttributionStatus.PROMPT_UNAVAILABLE,
            embeddingModelOf(decision), routingConfigVersionOf(decision)),
        CounterfactualReport.notComputed(CounterfactualStatus.PROMPT_UNAVAILABLE,
            embeddingModelOf(decision), routingConfigVersionOf(decision)),
        provenanceOf(decision)));
  }

  @Override
  public DecisionExplanation explainPrompt(String prompt) {
    // Both analyses embed the prompt against the same route index, so they see
    // the same rules; asking them in either order gives the same answer.
    return new DecisionExplanation(null,
        attribution.attribute(prompt),
        counterfactuals.explore(prompt),
        ExplanationProvenance.current(embedder.modelId(),
            RoutingConfigVersion.of(routingConfig.get()), routingCalibration()));
  }

  /**
   * Provenance of a stored decision comes from the <b>row</b>, not from the
   * running configuration — except the calibration, which is a live fact about
   * what is in force now and is what tells an operator that today's numbers and
   * this row's are not comparable.
   */
  private ExplanationProvenance provenanceOf(TracedDecision decision) {
    if (decision.routing() != null) {
      return ExplanationProvenance.of(decision.routing(), routingCalibration());
    }
    // Cache hit: the router never ran, so there is no routing config version to
    // report and the calibration that governed the request is the cache's.
    return ExplanationProvenance.current(embeddingModelOf(decision), null,
        calibrations.state(CalibrationTarget.CACHE));
  }

  private CalibrationState routingCalibration() {
    return calibrations.state(CalibrationTarget.ROUTING);
  }

  private static String embeddingModelOf(TracedDecision decision) {
    if (decision.routing() != null && decision.routing().embeddingModel() != null) {
      return decision.routing().embeddingModel();
    }
    return decision.cache() == null ? null : decision.cache().embeddingModel();
  }

  private static String routingConfigVersionOf(TracedDecision decision) {
    return decision.routing() == null
        ? null : decision.routing().routingConfigVersion();
  }
}
