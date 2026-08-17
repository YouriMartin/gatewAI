package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DecisionPurgeWorkerTest {

  private static final LeaderLock GRANTED = (task, work) -> {
    work.run();
    return true;
  };

  private static final LeaderLock DENIED = (task, work) -> false;

  @Mock
  private DecisionRecorder recorder;

  private DecisionPurgeWorker worker(LeaderLock lock, int retentionDays) {
    DecisionRecordingProperties properties = new DecisionRecordingProperties();
    properties.setRetentionDays(retentionDays);
    return new DecisionPurgeWorker(recorder, properties, lock);
  }

  @Test
  void purgesEverythingOlderThanTheRetentionWindow() {
    Instant before = Instant.now();

    worker(GRANTED, 90).purge();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(recorder).purgeOlderThan(cutoff.capture());
    Instant expected = before.minus(90, ChronoUnit.DAYS);
    // Same instant give or take the time the call took.
    assertTrue(
        !cutoff.getValue().isBefore(expected)
            && cutoff.getValue().isBefore(expected.plusSeconds(60)),
        "cutoff should be now minus the retention window");
  }

  @Test
  void aNodeThatIsNotTheLeaderPurgesNothing() {
    // With N replicas the purge is the same DELETE N times, and N log lines
    // describing one event (v3 lot B.4).
    worker(DENIED, 90).purge();

    verify(recorder, never()).purgeOlderThan(any());
  }

  @Test
  void aFailedPurgeIsSwallowedSoTheSchedulerSurvivesIt() {
    when(recorder.purgeOlderThan(any()))
        .thenThrow(new IllegalStateException("database gone"));

    assertDoesNotThrow(() -> worker(GRANTED, 90).purge());
  }

  @Test
  void aLockThatCannotBeTakenIsAlsoSwallowed() {
    // Failing to *reach* the lock is an operational problem, not a reason to
    // kill the scheduled task: the next tick tries again.
    LeaderLock unreachable = (task, work) -> {
      throw new IllegalStateException("database gone");
    };

    assertDoesNotThrow(() -> worker(unreachable, 90).purge());
    verify(recorder, never()).purgeOlderThan(any());
  }
}
