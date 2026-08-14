package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.ConformalStatus;
import io.github.yourimartin.gatewai.domain.model.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.TracedDecision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * The read side of the decision trace, against mocked repositories — what is
 * under test is the <b>merge</b>, not Spring Data.
 */
@ExtendWith(MockitoExtension.class)
class JpaDecisionHistoryTest {

  private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

  @Mock
  private SpringDataRoutingDecisionRepository routingRepository;

  @Mock
  private SpringDataCacheDecisionRepository cacheRepository;

  private JpaDecisionHistory history;

  @BeforeEach
  void setUp() {
    history = new JpaDecisionHistory(routingRepository, cacheRepository);
  }

  @Test
  @DisplayName("both halves of one request come back joined")
  void joinsBothHalvesOfARequest() {
    when(routingRepository.findFirstByCorrelationIdOrderByCreatedAtDesc("req-1"))
        .thenReturn(Optional.of(routingEntity("req-1", NOW)));
    when(cacheRepository.findFirstByCorrelationIdOrderByCreatedAtDesc("req-1"))
        .thenReturn(Optional.of(cacheEntity("req-1", NOW.minusMillis(5),
            CacheOutcome.MISS)));

    TracedDecision decision = history.byCorrelationId("req-1").orElseThrow();

    assertEquals("req-1", decision.correlationId());
    assertNotNull(decision.routing());
    assertNotNull(decision.cache());
    assertEquals(NOW, decision.at(), "the trace is dated by its latest row");
  }

  @Test
  @DisplayName("a cache hit has no routing half, and that is the finding")
  void cacheHitHasNoRoutingDecision() {
    when(routingRepository.findFirstByCorrelationIdOrderByCreatedAtDesc("req-2"))
        .thenReturn(Optional.empty());
    when(cacheRepository.findFirstByCorrelationIdOrderByCreatedAtDesc("req-2"))
        .thenReturn(Optional.of(cacheEntity("req-2", NOW, CacheOutcome.HIT)));

    TracedDecision decision = history.byCorrelationId("req-2").orElseThrow();

    assertNull(decision.routing());
    assertEquals(CacheOutcome.HIT, decision.cache().outcome());
  }

  @Test
  @DisplayName("nothing recorded is empty, not an exception")
  void unknownCorrelationIdIsEmpty() {
    when(routingRepository.findFirstByCorrelationIdOrderByCreatedAtDesc("gone"))
        .thenReturn(Optional.empty());
    when(cacheRepository.findFirstByCorrelationIdOrderByCreatedAtDesc("gone"))
        .thenReturn(Optional.empty());

    assertTrue(history.byCorrelationId("gone").isEmpty());
    assertTrue(history.byCorrelationId(null).isEmpty());
    assertTrue(history.byCorrelationId("  ").isEmpty());
  }

  @Test
  @DisplayName("the history includes requests the cache answered alone")
  void recentMergesBothTables() {
    when(routingRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
        .thenReturn(List.of(routingEntity("routed", NOW.minusSeconds(10))));
    when(cacheRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
        .thenReturn(List.of(cacheEntity("cached", NOW, CacheOutcome.HIT)));
    noCounterparts();

    List<TracedDecision> recent = history.recent(10);

    assertEquals(List.of("cached", "routed"),
        recent.stream().map(TracedDecision::correlationId).toList(),
        "newest first, and a cache hit is a decision too");
  }

  @Test
  @DisplayName("a half that fell outside the window is fetched, not ignored")
  void recentFillsMissingHalves() {
    when(routingRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
        .thenReturn(List.of(routingEntity("req-1", NOW)));
    when(cacheRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
        .thenReturn(List.of());
    when(cacheRepository.findByCorrelationIdInOrderByCreatedAtDesc(
        List.of("req-1")))
        .thenReturn(List.of(cacheEntity("req-1", NOW.minusMillis(3),
            CacheOutcome.MISS)));

    TracedDecision decision = history.recent(10).getFirst();

    assertNotNull(decision.cache(),
        "the cache looked at this request; the window just did not show it");
  }

  @Test
  @DisplayName("a row without a correlation id still appears, on its own")
  void rowsWithoutCorrelationIdAreNotMerged() {
    when(routingRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
        .thenReturn(List.of(routingEntity(null, NOW),
            routingEntity(null, NOW.minusSeconds(1))));
    when(cacheRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
        .thenReturn(List.of());
    noCounterparts();

    List<TracedDecision> recent = history.recent(10);

    assertEquals(2, recent.size(), "two requests, not one merged phantom");
    assertTrue(recent.stream().allMatch(d -> d.correlationId() == null));
  }

  @Test
  @DisplayName("the limit bounds the merged list, not just each table")
  void recentRespectsTheLimit() {
    when(routingRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
        .thenReturn(List.of(routingEntity("a", NOW), routingEntity("b",
            NOW.minusSeconds(1))));
    when(cacheRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
        .thenReturn(List.of(cacheEntity("c", NOW.minusSeconds(2),
            CacheOutcome.HIT)));
    noCounterparts();

    assertEquals(2, history.recent(2).size());
    assertTrue(history.recent(0).isEmpty());
  }

  /** No half is missing from the window in these cases. */
  private void noCounterparts() {
    lenient().when(cacheRepository.findByCorrelationIdInOrderByCreatedAtDesc(
        anyList())).thenReturn(List.of());
    lenient().when(routingRepository.findByCorrelationIdInOrderByCreatedAtDesc(
        anyList())).thenReturn(List.of());
  }

  private static RoutingDecisionEntity routingEntity(String correlationId,
                                                     Instant at) {
    return new RoutingDecisionEntity(new RoutingDecision(
        UUID.randomUUID(), correlationId, at.truncatedTo(ChronoUnit.MILLIS),
        "a".repeat(64), 42, "nomic-embed-text", "cfg-1",
        ClassificationStrategy.EMBEDDING, ClassificationStrategy.EMBEDDING,
        new ClassificationJustification.Embedding(List.of(), 0.81, 0.12, 0.60),
        DecisionReason.MATCH, ModelTier.CLOUD_PREMIUM, "qwen3:14b", 12L,
        List.of(ModelTier.CLOUD_PREMIUM), 0.05, null));
  }

  private static CacheDecisionEntity cacheEntity(String correlationId,
                                                 Instant at,
                                                 CacheOutcome outcome) {
    return new CacheDecisionEntity(new CacheDecision(
        UUID.randomUUID(), correlationId, at.truncatedTo(ChronoUnit.MILLIS),
        "b".repeat(64), outcome, 0.93, 0.42, 0.92,
        outcome == CacheOutcome.HIT ? "entry-1" : null,
        outcome == CacheOutcome.HIT ? 120L : null,
        outcome == CacheOutcome.HIT ? "origin-1" : null,
        "nomic-embed-text", ConformalStatus.SINGLETON));
  }
}
