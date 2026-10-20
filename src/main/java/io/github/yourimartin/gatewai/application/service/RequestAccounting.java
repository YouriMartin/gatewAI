package io.github.yourimartin.gatewai.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.carbon.CarbonZoneContext;
import io.github.yourimartin.gatewai.domain.model.carbon.CarbonZoneResolver;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.carbon.ModelSite;
import io.github.yourimartin.gatewai.domain.model.carbon.ProviderRegion;
import io.github.yourimartin.gatewai.domain.model.carbon.ResolvedCarbonZone;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.report.RequestLog;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;

/**
 * Green accounting, persistence and metrics for one served request — the same
 * code whichever path served it: the advisor chain or pass-through (ADR 0016,
 * v4 B.3). A report never has to know which path a request took.
 *
 * <p>Not a bean: each service builds its own from the same collaborators, which
 * keeps their constructors (and their tests) the place where the wiring shows.
 */
final class RequestAccounting {

  private final RequestLogRepository requestLogRepository;
  private final ModelRegistry modelRegistry;
  private final CarbonIntensityProvider carbonIntensityProvider;
  private final GreenAccountant greenAccountant;
  private final MetricsRecorder metricsRecorder;
  private final ProviderRegions providerRegions;
  private final CloudRegionZones cloudRegionZones;
  private final CarbonZoneResolver carbonZoneResolver = new CarbonZoneResolver();

  RequestAccounting(RequestLogRepository requestLogRepository,
                    ModelRegistry modelRegistry,
                    CarbonIntensityProvider carbonIntensityProvider,
                    GreenAccountant greenAccountant,
                    MetricsRecorder metricsRecorder,
                    ProviderRegions providerRegions,
                    CloudRegionZones cloudRegionZones) {
    this.requestLogRepository = requestLogRepository;
    this.modelRegistry = modelRegistry;
    this.carbonIntensityProvider = carbonIntensityProvider;
    this.greenAccountant = greenAccountant;
    this.metricsRecorder = metricsRecorder;
    this.providerRegions = providerRegions;
    this.cloudRegionZones = cloudRegionZones;
  }

  /**
   * Accounts, saves and counts one served request.
   *
   * @param model         the model that answered, as recorded in {@code request_log}
   * @param correlationId captured on the request thread: a stream completes where
   *                      the Scoped Value may be unbound
   * @param clientId      captured likewise
   */
  RequestLog record(LlmRequest request, String model, TokenUsage usage, boolean cacheHit,
                    long latencyMs, String correlationId, String clientId) {
    Accounted accounted = accountGreen(model, usage, cacheHit);
    RequestLog log = new RequestLog(
        UUID.randomUUID(),
        correlationId,
        Instant.now(),
        model,
        hashPrompt(request),
        usage.promptTokens(),
        usage.completionTokens(),
        usage.totalTokens(),
        latencyMs,
        clientId,
        accounted.metrics(),
        accounted.provenance(),
        cacheHit
    );
    requestLogRepository.save(log);
    metricsRecorder.record(log);
    return log;
  }

  /** The metrics of one request and the provenance that explains them (lot C.5). */
  private record Accounted(GreenMetrics metrics, GreenProvenance provenance) {
  }

  /**
   * Green accounting for one served request, at the grid that served it (v3 lot
   * C.3). The served model and the premium baseline are priced <b>separately</b>:
   * they may sit behind providers in different regions, and the baseline is a
   * counterfactual about the baseline's datacenter, not about this one.
   *
   * <p>The served model's provenance travels back with the metrics so the row can
   * be stored self-describing (lot C.5).
   */
  private Accounted accountGreen(String model, TokenUsage usage, boolean cacheHit) {
    ModelDefinition used = modelRegistry.findByModelId(model).orElse(null);
    ModelDefinition premiumBaseline =
        modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM).stream()
            .findFirst()
            .orElse(null);

    GreenProvenance usedProvenance = provenanceOf(used);
    GreenMetrics metrics = greenAccountant.account(
        siteOf(used, usedProvenance),
        siteOf(premiumBaseline, provenanceOf(premiumBaseline)),
        usage, cacheHit);
    return new Accounted(metrics, usedProvenance);
  }

  /**
   * Where {@code model} ran and how that was decided (v3 lots C.2–C.5): the grid, the
   * datacenter efficiency, the labels and the assumptions. A {@code null} zone means
   * the gateway's own default — the intensity provider owns that value, and naming a
   * zone here would change what it returns.
   */
  private GreenProvenance provenanceOf(ModelDefinition model) {
    if (model == null) {
      return GreenProvenance.UNKNOWN;
    }
    ProviderRegion provider =
        providerRegions.findByProvider(model.provider()).orElse(null);
    ResolvedCarbonZone resolved = resolveZone(provider);
    double intensity = resolved.zone() == null
        ? carbonIntensityProvider.gramsCo2PerKwh()
        : carbonIntensityProvider.gramsCo2PerKwh(resolved.zone());
    return new GreenProvenance(
        model.provider(),
        resolved.zone(),
        intensity,
        resolved.source(),
        resolved.dispatchZone(),
        provider == null || !provider.isDeclared() ? null : provider.provenance(),
        model.energySource(),
        provider == null ? null : provider.pue());
  }

  /** The site view of a provenance: what the carbon model needs to price a call. */
  private static ModelSite siteOf(ModelDefinition model, GreenProvenance provenance) {
    return model == null ? null : new ModelSite(
        model, provenance.gridIntensityGramsPerKwh(), provenance.pue());
  }

  /** Runs the resolution chain for one model. */
  ResolvedCarbonZone resolveZone(ModelDefinition model) {
    return resolveZone(model == null ? null
        : providerRegions.findByProvider(model.provider()).orElse(null));
  }

  private ResolvedCarbonZone resolveZone(ProviderRegion provider) {
    String dispatchZone = CarbonZoneContext.CURRENT.isBound()
        ? CarbonZoneContext.CURRENT.get() : null;
    String providerZone = provider == null || !provider.isDeclared()
        ? null : cloudRegionZones.zoneFor(provider.region()).orElse(null);
    return carbonZoneResolver.resolve(provider, providerZone, dispatchZone);
  }

  static String clientId() {
    if (RequestContext.CURRENT.isBound()) {
      return RequestContext.CURRENT.get().clientId();
    }
    return null;
  }

  /**
   * The ingress-assigned correlation id (v2 batch 0.3), null outside a bound
   * request context — an unauthenticated call, or a direct use-case invocation.
   */
  static String correlationId() {
    if (RequestContext.CURRENT.isBound()) {
      return RequestContext.CURRENT.get().traceId();
    }
    return null;
  }

  static String hashPrompt(LlmRequest request) {
    MessageDigest digest = sha256();
    request.messages().forEach(msg -> {
      digest.update(msg.role().getBytes(StandardCharsets.UTF_8));
      digest.update(msg.content().getBytes(StandardCharsets.UTF_8));
    });
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is guaranteed by the JDK", e);
    }
  }
}
