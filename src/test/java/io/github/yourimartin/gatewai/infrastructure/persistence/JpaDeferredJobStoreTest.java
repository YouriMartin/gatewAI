package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.DeferredJob;
import io.github.yourimartin.gatewai.domain.model.DeferredJobStatus;
import io.github.yourimartin.gatewai.domain.model.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaDeferredJobStoreTest {

  private static final long LEASE_MS = 60_000;

  @Mock
  private SpringDataDeferredJobRepository repository;

  @Captor
  private ArgumentCaptor<DeferredJobEntity> entityCaptor;

  private JpaDeferredJobStore store;

  @BeforeEach
  void setUp() {
    store = new JpaDeferredJobStore(repository, LEASE_MS, "node-a");
  }

  private static LlmRequest request() {
    return new LlmRequest("claude", List.of(new LlmMessage("user", "hi")),
        0.5, 128);
  }

  private static DeferredJob queued(UUID id) {
    return DeferredJob.queued(id, request(), "tenant", Instant.now());
  }

  @Test
  void saveInsertsAJobThatIsNotStoredYet() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    store.save(queued(id));

    verify(repository).save(entityCaptor.capture());
    DeferredJob stored = entityCaptor.getValue().toDomain();
    assertEquals(id, stored.id());
    assertEquals(DeferredJobStatus.QUEUED, stored.status());
    assertEquals("tenant", stored.clientId());
    assertEquals(request(), stored.request());
  }

  @Test
  void saveOfACompletionKeepsWhichNodeRanTheJob() {
    UUID id = UUID.randomUUID();
    DeferredJobEntity row = new DeferredJobEntity(queued(id));
    row.claim("SE", "node-b", Instant.now().plusSeconds(60));
    when(repository.findById(id)).thenReturn(Optional.of(row));

    LlmResponse response =
        new LlmResponse("qwen", "Hi!", "stop", 1, 1, 2, false);
    store.save(row.toDomain().completed(response, Instant.now()));

    assertEquals("node-b", row.claimedBy(),
        "the write that finishes a job must not erase where it ran");
    assertEquals(DeferredJobStatus.COMPLETED, row.toDomain().status());
    assertEquals("Hi!", row.toDomain().result().content());
    assertNull(row.leaseExpiresAt(),
        "a terminal job has nothing left to reclaim");
  }

  @Test
  void claimLocksTheNextQueuedJobAndLeasesItToThisNode() {
    UUID id = UUID.randomUUID();
    DeferredJobEntity row = new DeferredJobEntity(queued(id));
    when(repository.lockNextQueuedId()).thenReturn(Optional.of(id));
    when(repository.findById(id)).thenReturn(Optional.of(row));
    when(repository.saveAndFlush(row)).thenReturn(row);

    Instant before = Instant.now();
    DeferredJob claimed = store.claimNextQueued("SE").orElseThrow();

    assertEquals(DeferredJobStatus.RUNNING, claimed.status());
    assertEquals("SE", claimed.chosenZone());
    assertEquals("node-a", row.claimedBy());
    assertNotNull(row.leaseExpiresAt());
    assertFalse(row.leaseExpiresAt().isBefore(before.plusMillis(LEASE_MS)),
        "the lease must start when the work does, not before");
  }

  @Test
  void claimIsEmptyWhenNothingIsQueued() {
    when(repository.lockNextQueuedId()).thenReturn(Optional.empty());

    assertTrue(store.claimNextQueued("SE").isEmpty());
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void requeueAsksForRunningJobsWhoseLeaseIsInThePast() {
    ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
    when(repository.requeueExpiredLeases(any(), any(), any())).thenReturn(3);

    Instant before = Instant.now();
    assertEquals(3, store.requeueExpiredLeases());

    verify(repository).requeueExpiredLeases(
        eq(DeferredJobStatus.QUEUED), eq(DeferredJobStatus.RUNNING),
        now.capture());
    assertFalse(now.getValue().isBefore(before));
  }

  @Test
  void findMapsTheStoredRow() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id))
        .thenReturn(Optional.of(new DeferredJobEntity(queued(id))));

    DeferredJob found = store.find(id).orElseThrow();

    assertEquals(id, found.id());
    assertEquals(request(), found.request());
  }
}
