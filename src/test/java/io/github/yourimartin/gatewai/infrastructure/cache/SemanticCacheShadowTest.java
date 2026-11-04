package io.github.yourimartin.gatewai.infrastructure.cache;

import static io.github.yourimartin.gatewai.infrastructure.cache.SemanticCacheScopeTest.NO_METRICS;
import static io.github.yourimartin.gatewai.infrastructure.cache.SemanticCacheScopeTest.REGISTRY;
import static io.github.yourimartin.gatewai.infrastructure.cache.SemanticCacheScopeTest.recording;
import static io.github.yourimartin.gatewai.infrastructure.cache.SemanticCacheScopeTest.request;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.InMemoryConversationAffinityStore;
import io.github.yourimartin.gatewai.domain.model.client.ClientMode;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.vectorstore.SimpleVectorStore;

/**
 * The cache under a shadow client's replay (v4 D.1). The chain below it answers
 * as the shadow replay does — with the answer the client received — and the cache
 * decides, records and stores exactly as it would have: so it warms up in shadow,
 * and the first enforced request after the switch finds what shadow stored.
 */
class SemanticCacheShadowTest {

  private static final List<Message> QUESTION = List.of(new UserMessage("What is a B-tree?"));

  private final List<CacheDecision> decisions = new ArrayList<>();
  private SemanticCacheAdvisor advisor;

  @BeforeEach
  void setUp() {
    advisor = new SemanticCacheAdvisor(
        SimpleVectorStore.builder(new SemanticCacheScopeTest.WindowedEmbeddingModel()).build(),
        new SemanticCacheProperties(),
        new CacheDecisionTracer(recording(decisions), NO_METRICS),
        CalibrationFixtures.none(0.92), REGISTRY, text -> true,
        new InMemoryConversationAffinityStore());
  }

  @Test
  void aShadowMissStoresTheRealAnswerAndTheNextShadowRequestWouldHitIt() {
    SemanticCacheScopeTest.CountingChain replay = new SemanticCacheScopeTest.CountingChain();

    call(ClientMode.SHADOW, replay);
    call(ClientMode.SHADOW, replay);

    assertEquals(1, replay.calls.get(), "the would-hit never reaches the replay");
    assertEquals(List.of(CacheOutcome.MISS, CacheOutcome.HIT),
        decisions.stream().map(CacheDecision::outcome).toList());
    assertEquals(List.of(ClientMode.SHADOW, ClientMode.SHADOW),
        decisions.stream().map(CacheDecision::mode).toList());
  }

  @Test
  void whatShadowStoredIsServedOnceTheClientIsEnforced() {
    call(ClientMode.SHADOW, new SemanticCacheScopeTest.CountingChain());
    SemanticCacheScopeTest.CountingChain model = new SemanticCacheScopeTest.CountingChain();

    call(ClientMode.ENFORCE, model);

    assertEquals(0, model.calls.get());
    assertEquals(CacheOutcome.HIT, decisions.getLast().outcome());
    assertEquals(ClientMode.ENFORCE, decisions.getLast().mode());
  }

  private void call(ClientMode mode, SemanticCacheScopeTest.CountingChain chain) {
    ScopedValue.where(RequestContext.CURRENT, new RequestContext("tenant-1", "trace", mode))
        .run(() -> advisor.adviseCall(request(QUESTION), chain));
  }
}
