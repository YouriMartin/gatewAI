package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.yourimartin.gatewai.domain.model.DeferredJob;
import io.github.yourimartin.gatewai.domain.model.DeferredJobStatus;
import io.github.yourimartin.gatewai.domain.model.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.port.out.DeferredJobStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The two properties of the deferred queue that only real SQL can demonstrate
 * (v3 lot B.2): {@code FOR UPDATE SKIP LOCKED} handing each job to exactly one
 * worker, and the lease sweep recovering a job whose worker never finished.
 *
 * <p>Needs Postgres, so it is {@code @Tag("integration")} — run with
 * {@code ./mvnw -Pit test}. The lease is set to expire immediately: the claim
 * itself only ever looks at {@code QUEUED} rows, so an expired lease changes
 * nothing until {@code requeueExpiredLeases()} is called, which is exactly what
 * lets both tests share one context.
 */
@Tag("integration")
@SpringBootTest(properties = {
    "spring.profiles.active=mock",
    "gatewai.dispatch.job-lease-ms=0"
})
class JpaDeferredJobStoreClaimTest {

  private static final int JOBS = 40;
  private static final int WORKERS = 2;

  @Autowired
  private DeferredJobStore store;

  @Autowired
  private SpringDataDeferredJobRepository repository;

  private final List<UUID> written = new ArrayList<>();

  @AfterEach
  void removeWhatThisTestWrote() {
    repository.deleteAllById(written);
  }

  private UUID submit() {
    UUID id = UUID.randomUUID();
    store.save(DeferredJob.queued(
        id,
        new LlmRequest("qwen", List.of(new LlmMessage("user", "later, greener")),
            null, null),
        "tenant-it",
        Instant.now()));
    written.add(id);
    return id;
  }

  @Test
  void twoWorkersClaimingTheSameQueueRunEachJobExactlyOnce()
      throws InterruptedException {
    Set<UUID> submitted = new HashSet<>();
    for (int i = 0; i < JOBS; i++) {
      submitted.add(submit());
    }

    Queue<UUID> claimed = new ConcurrentLinkedQueue<>();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(WORKERS);
    ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
    try {
      for (int worker = 0; worker < WORKERS; worker++) {
        workers.execute(() -> {
          try {
            start.await();
            Optional<DeferredJob> job;
            while ((job = store.claimNextQueued("SE")).isPresent()) {
              claimed.add(job.get().id());
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        });
      }
      start.countDown();
      assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish");
    } finally {
      workers.shutdownNow();
    }

    // Every job claimed, and no job claimed twice. A duplicate here would mean
    // SKIP LOCKED is not doing its job and a client would be billed twice for
    // one deferred request.
    List<UUID> mine = claimed.stream().filter(submitted::contains).toList();
    assertEquals(JOBS, mine.size(), "a job was lost");
    assertEquals(JOBS, new HashSet<>(mine).size(), "a job was claimed twice");
  }

  @Test
  void aJobStrandedByAWorkerThatStoppedGoesBackToTheQueue() {
    UUID id = submit();
    DeferredJob claimed = store.claimNextQueued("SE").orElseThrow();
    assertEquals(DeferredJobStatus.RUNNING, claimed.status());

    // Nobody completes it — the worker holding the lease "died" here.
    assertTrue(store.requeueExpiredLeases() >= 1);

    DeferredJob recovered = store.find(id).orElseThrow();
    assertEquals(DeferredJobStatus.QUEUED, recovered.status());
    assertNull(recovered.chosenZone(),
        "a requeued job must not advertise the zone it was picked for");
    assertTrue(store.claimNextQueued("FR").isPresent(),
        "the recovered job has to be claimable again");
  }
}
