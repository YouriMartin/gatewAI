package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.CarbonAwareZoneSelector;
import io.github.yourimartin.gatewai.domain.model.CarbonZoneContext;
import io.github.yourimartin.gatewai.domain.model.DeferredJob;
import io.github.yourimartin.gatewai.domain.model.DeferredJobStatus;
import io.github.yourimartin.gatewai.domain.model.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.RequestContext;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.out.DeferredJobStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeferredChatServiceTest {

  private static final int MAX_PER_TICK = 3;

  @Mock
  private DeferredJobStore store;

  @Mock
  private ChatCompletionUseCase chatCompletion;

  @Captor
  private ArgumentCaptor<DeferredJob> jobCaptor;

  private DeferredChatService service;

  @BeforeEach
  void setUp() {
    service = new DeferredChatService(
        store, chatCompletion, new CarbonAwareZoneSelector(), MAX_PER_TICK);
  }

  private static LlmRequest request() {
    return new LlmRequest(
        "claude", List.of(new LlmMessage("user", "hi")), null, null);
  }

  private static LlmResponse response() {
    return new LlmResponse("claude-haiku", "Hi!", "stop", 1, 1, 2, false);
  }

  private static DeferredJob queued() {
    return DeferredJob.queued(
        UUID.randomUUID(), request(), "tenant", Instant.now());
  }

  /**
   * What the store's contract says a claim returns: the job already
   * {@code RUNNING} in the zone it was claimed for.
   */
  private static Optional<DeferredJob> claimed(String zone) {
    return Optional.of(queued().running(zone));
  }

  // ---- Submission ----

  @Test
  void submitQueuesJobAndReturnsId() {
    LlmRequest request = request();

    UUID id = service.submit(request);

    verify(store).save(jobCaptor.capture());
    DeferredJob saved = jobCaptor.getValue();
    assertEquals(id, saved.id());
    assertEquals(DeferredJobStatus.QUEUED, saved.status());
    assertSame(request, saved.request());
  }

  @Test
  void submitCapturesClientIdFromScopedValue() {
    RequestContext ctx = new RequestContext("tenant-7", null);
    ScopedValue.where(RequestContext.CURRENT, ctx)
        .run(() -> service.submit(request()));

    verify(store).save(jobCaptor.capture());
    assertEquals("tenant-7", jobCaptor.getValue().clientId());
  }

  // ---- Dispatch ----

  @Test
  void dispatchSelectsGreenestZoneAndCompletesJob() {
    LlmResponse response = response();
    String[] boundZone = new String[1];

    when(store.claimNextQueued("SE"))
        .thenReturn(claimed("SE"), Optional.empty());
    when(chatCompletion.complete(any())).thenAnswer(invocation -> {
      boundZone[0] = CarbonZoneContext.CURRENT.isBound()
          ? CarbonZoneContext.CURRENT.get() : null;
      return response;
    });

    service.dispatchPending(Map.of("FR", 56.0, "SE", 30.0, "DE", 380.0));

    assertEquals("SE", boundZone[0]);
    verify(store, atLeastOnce()).save(jobCaptor.capture());
    DeferredJob last = jobCaptor.getAllValues().getLast();
    assertEquals(DeferredJobStatus.COMPLETED, last.status());
    assertEquals("SE", last.chosenZone());
    assertSame(response, last.result());
  }

  @Test
  void dispatchMarksJobFailedWhenCompletionThrows() {
    when(store.claimNextQueued("FR"))
        .thenReturn(claimed("FR"), Optional.empty());
    when(chatCompletion.complete(any()))
        .thenThrow(new RuntimeException("boom"));

    service.dispatchPending(Map.of("FR", 56.0));

    verify(store, atLeastOnce()).save(jobCaptor.capture());
    DeferredJob last = jobCaptor.getAllValues().getLast();
    assertEquals(DeferredJobStatus.FAILED, last.status());
    assertEquals("boom", last.errorMessage());
  }

  @Test
  void dispatchWithoutZonesRunsWithoutBindingZone() {
    String[] boundZone = new String[]{"unset"};

    when(store.claimNextQueued(null))
        .thenReturn(claimed(null), Optional.empty());
    when(chatCompletion.complete(any())).thenAnswer(invocation -> {
      boundZone[0] = CarbonZoneContext.CURRENT.isBound()
          ? CarbonZoneContext.CURRENT.get() : null;
      return response();
    });

    service.dispatchPending(Map.of());

    assertNull(boundZone[0]);
    verify(store, atLeastOnce()).save(jobCaptor.capture());
    assertNull(jobCaptor.getAllValues().getLast().chosenZone());
  }

  @Test
  void dispatchNeverWritesTheRunningTransitionItself() {
    // The claim is that write, atomically. A second one from here would be a
    // non-atomic path into the same state (v3 lot B.2).
    when(store.claimNextQueued("FR"))
        .thenReturn(claimed("FR"), Optional.empty());
    when(chatCompletion.complete(any())).thenReturn(response());

    service.dispatchPending(Map.of("FR", 56.0));

    verify(store, times(1)).save(jobCaptor.capture());
    assertEquals(DeferredJobStatus.COMPLETED,
        jobCaptor.getValue().status());
  }

  @Test
  void dispatchKeepsClaimingUntilTheQueueIsEmpty() {
    when(store.claimNextQueued("FR")).thenReturn(
        claimed("FR"), claimed("FR"), Optional.empty());
    when(chatCompletion.complete(any())).thenReturn(response());

    service.dispatchPending(Map.of("FR", 56.0));

    // Two jobs run, and a third call establishes the queue was drained rather
    // than the loop stopping on a count.
    verify(store, times(3)).claimNextQueued("FR");
    verify(chatCompletion, times(2)).complete(any());
  }

  @Test
  void dispatchStopsAtTheTickCapAndLeavesTheRestQueued() {
    // Never empty: without a cap this loop would not end.
    when(store.claimNextQueued("FR")).thenReturn(claimed("FR"));
    when(chatCompletion.complete(any())).thenReturn(response());

    service.dispatchPending(Map.of("FR", 56.0));

    verify(store, times(MAX_PER_TICK)).claimNextQueued("FR");
    verify(chatCompletion, times(MAX_PER_TICK)).complete(any());
  }

  @Test
  void dispatchRequeuesStrandedJobsBeforeClaiming() {
    List<String> order = new ArrayList<>();
    when(store.requeueExpiredLeases()).thenAnswer(invocation -> {
      order.add("requeue");
      return 2;
    });
    when(store.claimNextQueued("FR")).thenAnswer(invocation -> {
      order.add("claim");
      return Optional.empty();
    });

    service.dispatchPending(Map.of("FR", 56.0));

    // Order matters: a job stranded by a dead node has to be back in the queue
    // before this tick looks at it, or it waits a whole interval for nothing.
    assertEquals(List.of("requeue", "claim"), order);
  }

  @Test
  void anEmptyQueueRunsNothing() {
    when(store.claimNextQueued("FR")).thenReturn(Optional.empty());

    service.dispatchPending(Map.of("FR", 56.0));

    verify(chatCompletion, never()).complete(any());
    verify(store, never()).save(any());
  }
}
