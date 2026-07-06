package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        store, chatCompletion, new CarbonAwareZoneSelector());
  }

  private static LlmRequest request() {
    return new LlmRequest(
        "claude", List.of(new LlmMessage("user", "hi")), null, null);
  }

  private static LlmResponse response() {
    return new LlmResponse("claude-haiku", "Hi!", "stop", 1, 1, 2, false);
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
    DeferredJob job = DeferredJob.queued(
        UUID.randomUUID(), request(), "tenant", Instant.now());
    LlmResponse response = response();
    String[] boundZone = new String[1];

    when(store.findQueued()).thenReturn(List.of(job));
    when(chatCompletion.complete(job.request())).thenAnswer(invocation -> {
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
    DeferredJob job = DeferredJob.queued(
        UUID.randomUUID(), request(), "tenant", Instant.now());

    when(store.findQueued()).thenReturn(List.of(job));
    when(chatCompletion.complete(job.request()))
        .thenThrow(new RuntimeException("boom"));

    service.dispatchPending(Map.of("FR", 56.0));

    verify(store, atLeastOnce()).save(jobCaptor.capture());
    DeferredJob last = jobCaptor.getAllValues().getLast();
    assertEquals(DeferredJobStatus.FAILED, last.status());
    assertEquals("boom", last.errorMessage());
  }

  @Test
  void dispatchWithoutZonesRunsWithoutBindingZone() {
    DeferredJob job = DeferredJob.queued(
        UUID.randomUUID(), request(), "tenant", Instant.now());
    String[] boundZone = new String[]{"unset"};

    when(store.findQueued()).thenReturn(List.of(job));
    when(chatCompletion.complete(job.request())).thenAnswer(invocation -> {
      boundZone[0] = CarbonZoneContext.CURRENT.isBound()
          ? CarbonZoneContext.CURRENT.get() : null;
      return response();
    });

    service.dispatchPending(Map.of());

    assertNull(boundZone[0]);
    verify(store, atLeastOnce()).save(jobCaptor.capture());
    assertNull(jobCaptor.getAllValues().getLast().chosenZone());
  }
}
