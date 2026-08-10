package io.github.yourimartin.gatewai.infrastructure.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.PromptHash;
import io.github.yourimartin.gatewai.domain.model.RequestContext;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

@ExtendWith(MockitoExtension.class)
class CacheDecisionTracerTest {

  @Mock
  private DecisionRecorder recorder;

  @Captor
  private ArgumentCaptor<CacheDecision> captor;

  private CacheDecisionTracer tracer;

  @BeforeEach
  void setUp() {
    tracer = new CacheDecisionTracer(recorder);
  }

  @Test
  void recordsAHitWithTheServedEntryAndTheRunnerUp() {
    Document best = scored("answered before", 0.95,
        Map.of(SemanticCacheAdvisor.CORRELATION_ID_KEY, "origin-corr",
            SemanticCacheAdvisor.CREATED_AT_KEY,
            Instant.now().minusSeconds(120).toEpochMilli()));
    Document second = scored("something else", 0.41, Map.of());

    tracer.decided("q", List.of(best, second), best, 0.92);

    CacheDecision decision = captured();
    assertEquals(CacheOutcome.HIT, decision.outcome());
    assertEquals(0.95, decision.similarityScore());
    assertEquals(0.41, decision.runnerUpScore());
    assertEquals(0.92, decision.threshold());
    assertEquals(best.getId(), decision.matchedEntryId());
    assertTrue(decision.matchedEntryAgeSeconds() >= 119);
    // The link that makes a hit auditable back to the answer's own routing.
    assertEquals("origin-corr", decision.originCorrelationId());
  }

  @Test
  void recordsAMissWithTheScoreThatWasNotEnough() {
    Document near = scored("close but no", 0.90, Map.of());

    tracer.decided("q", List.of(near), null, 0.92);

    CacheDecision decision = captured();
    assertEquals(CacheOutcome.MISS, decision.outcome());
    assertEquals(0.90, decision.similarityScore());
    assertNull(decision.matchedEntryId());
    assertNull(decision.originCorrelationId());
  }

  @Test
  void anEmptyCacheIsAMissWithNoScores() {
    tracer.decided("q", List.of(), null, 0.92);

    CacheDecision decision = captured();
    assertEquals(CacheOutcome.MISS, decision.outcome());
    assertEquals(0.0, decision.similarityScore());
    assertNull(decision.runnerUpScore());
  }

  @Test
  void aSingleCandidateHasNoRunnerUp() {
    tracer.decided("q", List.of(scored("only one", 0.99, Map.of())),
        null, 0.92);

    assertNull(captured().runnerUpScore());
  }

  @Test
  void bypassAndErrorAreDistinctOutcomes() {
    tracer.bypassed("  ");
    assertEquals(CacheOutcome.BYPASS, captured().outcome());

    tracer = new CacheDecisionTracer(recorder);
    tracer.failed("q", 0.92);
    assertEquals(CacheOutcome.ERROR, captured().outcome());
  }

  @Test
  void storesAHashNeverThePrompt() {
    tracer.decided("what is my password", List.of(), null, 0.92);

    CacheDecision decision = captured();
    assertEquals(PromptHash.of("what is my password"), decision.promptHash());
    assertEquals(64, decision.promptHash().length());
  }

  @Test
  void carriesTheCorrelationIdOfTheRequestBeingServed() {
    ScopedValue.where(RequestContext.CURRENT,
        new RequestContext("client-1", "corr-42"))
        .run(() -> tracer.decided("q", List.of(), null, 0.92));

    assertEquals("corr-42", captured().correlationId());
  }

  @Test
  void aFailingRecorderNeverReachesTheCaller() {
    doThrow(new IllegalStateException("boom"))
        .when(recorder).record(org.mockito.ArgumentMatchers.any(CacheDecision.class));

    assertDoesNotThrow(() -> tracer.decided("q", List.of(), null, 0.92));
    assertDoesNotThrow(() -> tracer.bypassed("q"));
    assertDoesNotThrow(() -> tracer.failed("q", 0.92));
  }

  @Test
  void aMissingCreatedAtLeavesTheAgeUnknownRatherThanWrong() {
    Document best = scored("no timestamp", 0.99, Map.of());

    tracer.decided("q", List.of(best), best, 0.92);

    assertNull(captured().matchedEntryAgeSeconds());
  }

  private CacheDecision captured() {
    verify(recorder, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
    CacheDecision decision = captor.getValue();
    assertNotNull(decision);
    return decision;
  }

  private static Document scored(String text, double score,
                                 Map<String, Object> metadata) {
    return Document.builder()
        .text(text)
        .metadata(new HashMap<>(metadata))
        .score(score)
        .build();
  }
}
