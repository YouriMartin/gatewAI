package io.github.yourimartin.gatewai.application.service;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.Counterfactual;
import io.github.yourimartin.gatewai.domain.model.CounterfactualReport;
import io.github.yourimartin.gatewai.domain.model.CounterfactualStatus;
import io.github.yourimartin.gatewai.domain.model.Counterfactuals;
import io.github.yourimartin.gatewai.domain.model.EmbeddedRoute;
import io.github.yourimartin.gatewai.domain.model.RouteScoring;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.RoutingConfigVersion;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.port.in.RouteCounterfactualUseCase;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Where a request would have gone instead (v2 batch 8).
 *
 * <p>Semantic routing already ranks every route against the request; deciding
 * only ever uses the top of that list. The rest of it is the counterfactual —
 * the tier that came second, the example that would have taken the request
 * there, and the gap that separated them. Reading it costs the ranking, which is
 * one embedding call against an index that is already built, so this is the
 * cheap half of the explanation pair (see {@code OcclusionAttributionService}
 * for the expensive one).
 *
 * <p><b>No cache.</b> Attribution caches because it costs n + 1 embeddings;
 * caching one is not worth a key that would then have to be kept in step with
 * the routing config, so this recomputes and is always current.
 *
 * <p>The examples it returns come from route <b>configuration</b> — the index
 * holds nothing else. That is a property worth stating because the same
 * structure would happily hold user prompts, and an explanation that quoted one
 * client's request back to another would be a data leak wearing the costume of a
 * feature.
 */
@Service
class RouteCounterfactualService implements RouteCounterfactualUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(RouteCounterfactualService.class);

  private final TextEmbedder embedder;
  private final RoutingConfigPort routingConfig;
  private final SemanticRouteIndex index;
  private final int maxAlternatives;

  RouteCounterfactualService(
      TextEmbedder embedder,
      RoutingConfigPort routingConfig,
      SemanticRouteIndex index,
      @Value("${gatewai.counterfactuals.max-alternatives:3}") int maxAlternatives) {
    this.embedder = embedder;
    this.routingConfig = routingConfig;
    this.index = index;
    this.maxAlternatives = maxAlternatives;
  }

  @Override
  public CounterfactualReport explore(String prompt) {
    RoutingConfig config = routingConfig.get();
    String version = RoutingConfigVersion.of(config);

    if (prompt == null || prompt.isBlank()) {
      return CounterfactualReport.notComputed(
          CounterfactualStatus.EMPTY_PROMPT, embedder.modelId(), version);
    }
    if (!config.decidesBySimilarity()) {
      // Keywords and a model's own reasoning rank nothing, so nothing came
      // second. Saying that beats an empty list, which reads as a failure.
      return CounterfactualReport.notComputed(
          CounterfactualStatus.NOT_APPLICABLE_STRATEGY, embedder.modelId(),
          version);
    }
    List<SemanticRoute> routes = config.usableRoutes();
    if (routes.isEmpty()) {
      return CounterfactualReport.notComputed(
          CounterfactualStatus.NO_ROUTES_CONFIGURED, embedder.modelId(),
          version);
    }

    List<EmbeddedRoute> embeddedRoutes = index.forRoutes(routes);
    List<ClassificationJustification.RouteCandidate> ranked =
        RouteScoring.rank(embedder.embed(prompt), embeddedRoutes);
    if (ranked.isEmpty()) {
      return CounterfactualReport.notComputed(
          CounterfactualStatus.NO_ROUTES_CONFIGURED, embedder.modelId(),
          version);
    }

    ClassificationJustification.RouteCandidate chosen = ranked.getFirst();
    List<Counterfactual> alternatives =
        Counterfactuals.from(ranked, maxAlternatives);

    LOG.debug("Counterfactuals for route {}: {} alternative outcome(s)",
        chosen.route(), alternatives.size());

    return new CounterfactualReport(
        alternatives.isEmpty()
            ? CounterfactualStatus.NO_ALTERNATIVE_TIER
            : CounterfactualStatus.COMPUTED,
        chosen.route(), chosen.tier(), chosen.bestUtterance(), chosen.score(),
        alternatives, embedder.modelId(), version);
  }
}
