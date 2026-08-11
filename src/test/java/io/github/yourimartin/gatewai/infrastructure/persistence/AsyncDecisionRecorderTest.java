package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.ConformalStatus;
import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.HeuristicRule;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

@ExtendWith(MockitoExtension.class)
class AsyncDecisionRecorderTest {

  @Mock
  private SpringDataRoutingDecisionRepository routingRepository;

  @Mock
  private SpringDataCacheDecisionRepository cacheRepository;

  private MeterRegistry meterRegistry;
  private DecisionRecordingProperties properties;
  private AsyncDecisionRecorder recorder;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    properties = new DecisionRecordingProperties();
    // Runs the submitted task inline, so the assertions see the effect.
    TaskExecutor executor = new SyncTaskExecutor();
    recorder = new AsyncDecisionRecorder(routingRepository, cacheRepository,
        executor, meterRegistry, properties);
  }

  @Test
  void writesARoutingDecision() {
    recorder.record(routingDecision());

    verify(routingRepository).save(any(RoutingDecisionEntity.class));
  }

  @Test
  void writesACacheDecision() {
    recorder.record(cacheDecision());

    verify(cacheRepository).save(any(CacheDecisionEntity.class));
  }

  @Test
  void aFailingStoreNeverReachesTheCaller() {
    // The load-bearing guarantee: the database being down must cost the trace,
    // not the completion.
    when(routingRepository.save(any(RoutingDecisionEntity.class)))
        .thenThrow(new IllegalStateException("database is down"));

    assertDoesNotThrow(() -> recorder.record(routingDecision()));
    assertEquals(1.0, recorder.failureCount("routing"));
  }

  @Test
  void aFailingCacheWriteIsCountedSeparately() {
    when(cacheRepository.save(any(CacheDecisionEntity.class)))
        .thenThrow(new IllegalStateException("database is down"));

    assertDoesNotThrow(() -> recorder.record(cacheDecision()));
    assertEquals(1.0, recorder.failureCount("cache"));
    assertEquals(0.0, recorder.failureCount("routing"));
  }

  @Test
  void aRejectedSubmissionIsAlsoAFailureToRecord() {
    TaskExecutor rejecting = task -> {
      throw new IllegalStateException("executor shut down");
    };
    recorder = new AsyncDecisionRecorder(routingRepository, cacheRepository,
        rejecting, meterRegistry, properties);

    assertDoesNotThrow(() -> recorder.record(routingDecision()));
    assertEquals(1.0, recorder.failureCount("routing"));
    verify(routingRepository, never()).save(any());
  }

  @Test
  void disablingTracingWritesNothing() {
    properties.setEnabled(false);

    recorder.record(routingDecision());
    recorder.record(cacheDecision());

    verify(routingRepository, never()).save(any());
    verify(cacheRepository, never()).save(any());
  }

  @Test
  void purgeCountsBothTables() {
    Instant cutoff = Instant.now();
    when(routingRepository.deleteByCreatedAtBefore(cutoff)).thenReturn(3);
    when(cacheRepository.deleteByCreatedAtBefore(cutoff)).thenReturn(4);

    assertEquals(7, recorder.purgeOlderThan(cutoff));
  }

  private static RoutingDecision routingDecision() {
    return new RoutingDecision(
        UUID.randomUUID(), "corr-1", Instant.now(), "a".repeat(64), 12,
        "nomic-embed-text", "cfgversion0000001",
        ClassificationStrategy.EMBEDDING, ClassificationStrategy.EMBEDDING,
        ClassificationJustification.Heuristic.of(HeuristicRule.DEFAULT),
        DecisionReason.MATCH, ModelTier.LOCAL, "qwen2.5:0.5b", 4L, null, null);
  }

  private static CacheDecision cacheDecision() {
    return new CacheDecision(
        UUID.randomUUID(), "corr-1", Instant.now(), "b".repeat(64),
        CacheOutcome.MISS, 0.4, 0.2, 0.92, null, null, null,
        "nomic-embed-text", ConformalStatus.NOT_CALIBRATED);
  }
}
