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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Carbon-aware deferred execution (Phase 4.4). Submission queues a job and
 * returns immediately; a worker later calls {@link #dispatchPending(Map)},
 * which selects the greenest zone and runs each job through the standard
 * {@link ChatCompletionUseCase}. The chosen zone is bound via
 * {@link CarbonZoneContext} so emissions are accounted at that zone's intensity
 * — no duplication of the metering path.
 *
 * <p>Since v3 lot B.2 the queue is a Postgres table and jobs are taken by
 * <b>claiming</b>, so the same queue can be worked by every replica without a job
 * running twice. The service is unaware of which node it is; that belongs to the
 * store.
 */
@Service
class DeferredChatService implements SubmitDeferredRequestUseCase,
    GetDeferredJobUseCase, DispatchDeferredJobsUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(DeferredChatService.class);

  private final DeferredJobStore store;
  private final ChatCompletionUseCase chatCompletion;
  private final CarbonAwareZoneSelector zoneSelector;
  private final int maxJobsPerTick;

  DeferredChatService(
      DeferredJobStore store,
      ChatCompletionUseCase chatCompletion,
      CarbonAwareZoneSelector zoneSelector,
      @Value("${gatewai.dispatch.max-jobs-per-tick:20}") int maxJobsPerTick) {
    this.store = store;
    this.chatCompletion = chatCompletion;
    this.zoneSelector = zoneSelector;
    this.maxJobsPerTick = maxJobsPerTick;
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

  /**
   * Runs what the queue holds, one claim at a time (v3 lot B.2).
   *
   * <p>The loop claims rather than lists: the store hands out a job by flipping
   * it to {@code RUNNING} atomically, so a second worker on another node sees an
   * empty queue instead of the same job. It stops at
   * {@code gatewai.dispatch.max-jobs-per-tick} so a busy queue cannot keep one
   * tick — and therefore one node — running indefinitely; the rest is picked up
   * on the next tick, by whichever node asks first.
   */
  @Override
  public void dispatchPending(Map<String, Double> zoneIntensities) {
    reclaimStrandedJobs();
    String zone = zoneSelector.greenest(zoneIntensities).orElse(null);
    for (int dispatched = 0; dispatched < maxJobsPerTick; dispatched++) {
      Optional<DeferredJob> claimed = store.claimNextQueued(zone);
      if (claimed.isEmpty()) {
        return;
      }
      runClaimed(claimed.get());
    }
    LOG.debug("Dispatch tick stopped at {} jobs; the rest waits for the next one",
        maxJobsPerTick);
  }

  /**
   * A node that died mid-job left its rows {@code RUNNING} with nothing to
   * release them. Requeueing on every tick means recovery does not depend on the
   * dead node ever coming back.
   */
  private void reclaimStrandedJobs() {
    int requeued = store.requeueExpiredLeases();
    if (requeued > 0) {
      LOG.warn("Requeued {} deferred job(s) whose lease expired — a worker "
          + "holding them stopped without finishing", requeued);
    }
  }

  /**
   * Runs a job this node already owns. There is no {@code RUNNING} write here:
   * the claim was that write, and doing it again from the application layer would
   * be a second, non-atomic path into the same state.
   */
  private void runClaimed(DeferredJob job) {
    String zone = job.chosenZone();
    try {
      LlmResponse response = execute(job, zone);
      store.save(job.completed(response, Instant.now()));
      LOG.info("Completed deferred job {} (zone={})", job.id(), zone);
    } catch (RuntimeException e) {
      store.save(job.failed(e.getMessage(), Instant.now()));
      LOG.warn("Deferred job {} failed: {}", job.id(), e.getMessage());
    }
  }

  private LlmResponse execute(DeferredJob job, String zone) {
    // The job runs long after the submitting HTTP request is gone, so its own
    // id is the correlation id (v2 batch 0.3) — the client already holds it
    // from the submit response, and it ties the log back to the job.
    RequestContext ctx =
        new RequestContext(job.clientId(), job.id().toString());
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
