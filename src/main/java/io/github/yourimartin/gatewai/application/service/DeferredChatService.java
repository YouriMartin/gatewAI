package io.github.yourimartin.gatewai.application.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.CarbonAwareZoneSelector;
import io.github.yourimartin.gatewai.domain.model.CarbonZoneContext;
import io.github.yourimartin.gatewai.domain.model.DeferredJob;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.RequestContext;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.DispatchDeferredJobsUseCase;
import io.github.yourimartin.gatewai.domain.port.in.GetDeferredJobUseCase;
import io.github.yourimartin.gatewai.domain.port.in.SubmitDeferredRequestUseCase;
import io.github.yourimartin.gatewai.domain.port.out.DeferredJobStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Carbon-aware deferred execution (Phase 4.4). Submission queues a job and
 * returns immediately; a worker later calls {@link #dispatchPending(Map)},
 * which selects the greenest zone and runs each job through the standard
 * {@link ChatCompletionUseCase}. The chosen zone is bound via
 * {@link CarbonZoneContext} so emissions are accounted at that zone's intensity
 * — no duplication of the metering path.
 */
@Service
class DeferredChatService implements SubmitDeferredRequestUseCase,
    GetDeferredJobUseCase, DispatchDeferredJobsUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(DeferredChatService.class);

  private final DeferredJobStore store;
  private final ChatCompletionUseCase chatCompletion;
  private final CarbonAwareZoneSelector zoneSelector;

  DeferredChatService(DeferredJobStore store,
                      ChatCompletionUseCase chatCompletion,
                      CarbonAwareZoneSelector zoneSelector) {
    this.store = store;
    this.chatCompletion = chatCompletion;
    this.zoneSelector = zoneSelector;
  }

  @Override
  public UUID submit(LlmRequest request) {
    UUID id = UUID.randomUUID();
    DeferredJob job =
        DeferredJob.queued(id, request, currentClientId(), Instant.now());
    store.save(job);
    LOG.info("Queued deferred job {}", id);
    return id;
  }

  @Override
  public Optional<DeferredJob> find(UUID id) {
    return store.find(id);
  }

  @Override
  public void dispatchPending(Map<String, Double> zoneIntensities) {
    String zone = zoneSelector.greenest(zoneIntensities).orElse(null);
    for (DeferredJob job : store.findQueued()) {
      runJob(job, zone);
    }
  }

  private void runJob(DeferredJob job, String zone) {
    store.save(job.running(zone));
    try {
      LlmResponse response = execute(job, zone);
      store.save(job.running(zone).completed(response, Instant.now()));
      LOG.info("Completed deferred job {} (zone={})", job.id(), zone);
    } catch (RuntimeException e) {
      store.save(job.running(zone).failed(e.getMessage(), Instant.now()));
      LOG.warn("Deferred job {} failed: {}", job.id(), e.getMessage());
    }
  }

  private LlmResponse execute(DeferredJob job, String zone) {
    RequestContext ctx = new RequestContext(job.clientId(), null);
    ScopedValue.Carrier carrier = ScopedValue.where(RequestContext.CURRENT, ctx);
    if (zone != null) {
      carrier = carrier.where(CarbonZoneContext.CURRENT, zone);
    }
    LlmResponse[] holder = new LlmResponse[1];
    carrier.run(() -> holder[0] = chatCompletion.complete(job.request()));
    return holder[0];
  }

  private static String currentClientId() {
    return RequestContext.CURRENT.isBound()
        ? RequestContext.CURRENT.get().clientId() : null;
  }
}
