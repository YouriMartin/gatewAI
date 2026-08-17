package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What only a real database can show about the leader lock (v3 lot B.4): that a
 * second node asking while the first holds it skips instead of running, that the
 * lock is released when the work ends — including by failing — and that two tasks
 * do not block each other.
 *
 * <p>Two threads stand in for two replicas: two transactions on two connections,
 * which is what two nodes are as far as {@code pg_try_advisory_xact_lock} is
 * concerned. The timing is not left to chance — the second node always asks
 * <em>while</em> the first is inside its job, and the first waits for the answer
 * before releasing.
 *
 * <p>This is also the test that proves the lock is taken on the <b>same
 * connection as the work</b>: if {@code JdbcTemplate} did not join the
 * transaction, the lock would be released the instant it was taken and the second
 * node would run too.
 *
 * <p>Needs Postgres, so {@code @Tag("integration")} — run with
 * {@code ./mvnw -Pit test}.
 */
@Tag("integration")
@SpringBootTest(properties = "spring.profiles.active=mock")
class AdvisoryLeaderLockTest {

  @Autowired
  private LeaderLock leaderLock;

  @Test
  void aSecondNodeSkipsTheJobWhileTheFirstIsRunningIt() {
    AtomicInteger ran = new AtomicInteger();
    AtomicBoolean secondAcquired = new AtomicBoolean(true);
    CountDownLatch secondAnswered = new CountDownLatch(1);
    ExecutorService secondNode = Executors.newSingleThreadExecutor();

    try {
      boolean first = leaderLock.runIfLeader(LeaderTask.DECISION_PURGE, () -> {
        ran.incrementAndGet();
        secondNode.execute(() -> {
          secondAcquired.set(leaderLock.runIfLeader(
              LeaderTask.DECISION_PURGE, ran::incrementAndGet));
          secondAnswered.countDown();
        });
        assertTrue(awaitQuietly(secondAnswered), "the second node never answered");
      });
      assertTrue(first, "the first node should have taken the lock");
    } finally {
      secondNode.shutdownNow();
    }

    assertFalse(secondAcquired.get(), "two nodes ran the same job");
    assertEquals(1, ran.get(), "the job body ran more than once");
  }

  @Test
  void theLockIsFreeAgainOnceTheJobEnds() {
    // There is no leader to lose: the next tick on any node simply wins.
    assertTrue(leaderLock.runIfLeader(LeaderTask.DECISION_PURGE, () -> { }));
    assertTrue(leaderLock.runIfLeader(LeaderTask.DECISION_PURGE, () -> { }));
  }

  @Test
  void aJobThatThrowsStillReleasesTheLock() {
    assertThrows(IllegalStateException.class,
        () -> leaderLock.runIfLeader(LeaderTask.DECISION_PURGE, () -> {
          throw new IllegalStateException("boom");
        }));

    assertTrue(leaderLock.runIfLeader(LeaderTask.DECISION_PURGE, () -> { }),
        "a failed job must not strand the lock until the process restarts");
  }

  @Test
  void differentTasksDoNotBlockEachOther() {
    // One lock key per job, so a slow purge cannot delay the admin seed.
    assertNotEquals(LeaderTask.DECISION_PURGE.lockId(),
        LeaderTask.ADMIN_SEED.lockId(), "two tasks share a lock id");

    AtomicInteger ran = new AtomicInteger();
    CountDownLatch otherDone = new CountDownLatch(1);
    ExecutorService otherNode = Executors.newSingleThreadExecutor();

    try {
      leaderLock.runIfLeader(LeaderTask.DECISION_PURGE, () -> {
        otherNode.execute(() -> {
          if (leaderLock.runIfLeader(LeaderTask.ADMIN_SEED,
              ran::incrementAndGet)) {
            otherDone.countDown();
          }
        });
        assertTrue(awaitQuietly(otherDone), "the other task was blocked");
      });
    } finally {
      otherNode.shutdownNow();
    }

    assertEquals(1, ran.get());
  }

  private static boolean awaitQuietly(CountDownLatch latch) {
    try {
      return latch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
