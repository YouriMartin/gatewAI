package io.github.yourimartin.gatewai.infrastructure.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.DeferredJob;
import io.github.yourimartin.gatewai.domain.model.DeferredJobStatus;
import io.github.yourimartin.gatewai.domain.model.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryDeferredJobStoreTest {

  private InMemoryDeferredJobStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryDeferredJobStore();
  }

  private static DeferredJob queued(UUID id) {
    LlmRequest request = new LlmRequest(
        "claude", List.of(new LlmMessage("user", "hi")), null, null);
    return DeferredJob.queued(id, request, "tenant", Instant.now());
  }

  @Test
  void savesAndFindsById() {
    UUID id = UUID.randomUUID();
    store.save(queued(id));

    assertEquals(DeferredJobStatus.QUEUED, store.find(id).orElseThrow().status());
  }

  @Test
  void findReturnsEmptyForUnknownId() {
    assertTrue(store.find(UUID.randomUUID()).isEmpty());
  }

  @Test
  void saveReplacesExistingJobOnTransition() {
    UUID id = UUID.randomUUID();
    DeferredJob job = queued(id);
    store.save(job);
    store.save(job.running("FR"));

    assertEquals(DeferredJobStatus.RUNNING, store.find(id).orElseThrow().status());
  }

  @Test
  void findQueuedReturnsOnlyQueuedJobs() {
    UUID queuedId = UUID.randomUUID();
    UUID runningId = UUID.randomUUID();
    store.save(queued(queuedId));
    store.save(queued(runningId).running("FR"));

    List<DeferredJob> queued = store.findQueued();

    assertEquals(1, queued.size());
    assertEquals(queuedId, queued.getFirst().id());
  }
}
