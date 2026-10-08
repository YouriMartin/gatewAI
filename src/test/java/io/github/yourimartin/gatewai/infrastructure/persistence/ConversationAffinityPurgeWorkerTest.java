package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import io.github.yourimartin.gatewai.domain.port.out.ConversationAffinityStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationAffinityPurgeWorkerTest {

  @Mock
  private ConversationAffinityStore store;

  private ConversationAffinityPurgeWorker worker(LeaderLock lock, Duration ttl) {
    ConversationAffinityProperties properties = new ConversationAffinityProperties();
    properties.setConversationTtl(ttl);
    return new ConversationAffinityPurgeWorker(store, properties, lock);
  }

  @Test
  void purgesWhatWasLastSeenBeforeTheTtlUnderItsOwnLock() {
    LeaderTask[] taken = new LeaderTask[1];
    LeaderLock granted = (task, work) -> {
      taken[0] = task;
      work.run();
      return true;
    };
    Instant before = Instant.now();

    worker(granted, Duration.ofHours(24)).purge();

    assertEquals(LeaderTask.CONVERSATION_AFFINITY_PURGE, taken[0]);
    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(store).purgeLastSeenBefore(cutoff.capture());
    Instant expected = before.minus(Duration.ofHours(24));
    assertTrue(!cutoff.getValue().isBefore(expected)
            && cutoff.getValue().isBefore(expected.plusSeconds(60)),
        "cutoff should be now minus the TTL");
  }

  @Test
  void aNodeThatIsNotTheLeaderPurgesNothing() {
    worker((task, work) -> false, Duration.ofHours(24)).purge();
    verify(store, never()).purgeLastSeenBefore(any());
  }

  @Test
  void aFailingPurgeIsSwallowed() {
    when(store.purgeLastSeenBefore(any())).thenThrow(new IllegalStateException("db down"));
    LeaderLock granted = (task, work) -> {
      work.run();
      return true;
    };
    assertDoesNotThrow(() -> worker(granted, Duration.ofHours(1)).purge());
  }

  @Test
  void theLockIdIsItsOwn() {
    // Ids are permanent and must never be shared between jobs.
    assertEquals(3, LeaderTask.CONVERSATION_AFFINITY_PURGE.lockId());
  }
}
