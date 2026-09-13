package io.github.yourimartin.gatewai.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.github.yourimartin.gatewai.domain.model.CarbonZoneContext;
import io.github.yourimartin.gatewai.domain.model.CarbonZoneResolver;
import io.github.yourimartin.gatewai.domain.model.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.LlmStreamChunk;
import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelSite;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.ProviderRegion;
import io.github.yourimartin.gatewai.domain.model.RequestContext;
import io.github.yourimartin.gatewai.domain.model.RequestLog;
import io.github.yourimartin.gatewai.domain.model.ResolvedCarbonZone;
import io.github.yourimartin.gatewai.domain.model.TokenUsage;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.StreamChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.LlmClient;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;

import org.springframework.stereotype.Service;

@Service
class ChatCompletionService
    implements ChatCompletionUseCase, StreamChatCompletionUseCase {

  private final LlmClient llmClient;
  private final RequestLogRepository requestLogRepository;
  private final ModelRegistry modelRegistry;
  private final CarbonIntensityProvider carbonIntensityProvider;
  private final GreenAccountant greenAccountant;
  private final MetricsRecorder metricsRecorder;
  private final ProviderRegions providerRegions;
  private final CloudRegionZones cloudRegionZones;
  private final CarbonZoneResolver carbonZoneResolver = new CarbonZoneResolver();

  ChatCompletionService(LlmClient llmClient,
                        RequestLogRepository requestLogRepository,
                        ModelRegistry modelRegistry,
                        CarbonIntensityProvider carbonIntensityProvider,
                        GreenAccountant greenAccountant,
                        MetricsRecorder metricsRecorder,
                        ProviderRegions providerRegions,
                        CloudRegionZones cloudRegionZones) {
    this.llmClient = llmClient;
    this.requestLogRepository = requestLogRepository;
    this.modelRegistry = modelRegistry;
    this.carbonIntensityProvider = carbonIntensityProvider;
    this.greenAccountant = greenAccountant;
    this.metricsRecorder = metricsRecorder;
    this.providerRegions = providerRegions;
    this.cloudRegionZones = cloudRegionZones;
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    long startNanos = System.nanoTime();

    LlmResponse response = llmClient.call(request);

    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    String promptHash = hashPrompt(request);
    String clientId = resolveClientId();
    Accounted accounted = accountGreen(response.model(), new TokenUsage(
        response.promptTokens(), response.completionTokens(), response.totalTokens()),
        response.cacheHit());

    RequestLog log = new RequestLog(
        UUID.randomUUID(),
        resolveCorrelationId(),
        Instant.now(),
        response.model(),
        promptHash,
        response.promptTokens(),
        response.completionTokens(),
        response.totalTokens(),
        latencyMs,
        clientId,
        accounted.metrics(),
        accounted.provenance(),
        response.cacheHit()
    );
    requestLogRepository.save(log);
    metricsRecorder.record(log);

    return response;
  }

  /**
   * Streams the response (Phase 7.5). Forwards each chunk to {@code onChunk} and,
   * once the stream completes, records the same green accounting / persistence /
   * metrics as the blocking path — from the terminal chunk's model + token usage.
   */
  @Override
  public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> onChunk) {
    long startNanos = System.nanoTime();
    String clientId = resolveClientId();
    String correlationId = resolveCorrelationId();
    AtomicReference<LlmStreamChunk> lastChunk = new AtomicReference<>();

    llmClient.stream(request, chunk -> {
      lastChunk.set(chunk);
      onChunk.accept(chunk);
    });

    LlmStreamChunk last = lastChunk.get();
    if (last == null) {
      return;
    }

    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    Accounted accounted = accountGreen(last.model(), new TokenUsage(
        last.promptTokens(), last.completionTokens(), last.totalTokens()),
        last.cacheHit());

    RequestLog log = new RequestLog(
        UUID.randomUUID(),
        correlationId,
        Instant.now(),
        last.model(),
        hashPrompt(request),
        last.promptTokens(),
        last.completionTokens(),
        last.totalTokens(),
        latencyMs,
        clientId,
        accounted.metrics(),
        accounted.provenance(),
        last.cacheHit()
    );
    requestLogRepository.save(log);
    metricsRecorder.record(log);
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

  /**
   * Runs the resolution chain for one model. Package-visible so a test can assert
   * the zone <em>and</em> the recorded-but-not-applied dispatch zone, which is the
   * distinction lot C.5 will persist.
   */
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

  private static String resolveClientId() {
    if (RequestContext.CURRENT.isBound()) {
      return RequestContext.CURRENT.get().clientId();
    }
    return null;
  }

  /**
   * The ingress-assigned correlation id (v2 batch 0.3), null outside a bound
   * request context — an unauthenticated call, or a direct use-case invocation.
   */
  private static String resolveCorrelationId() {
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
