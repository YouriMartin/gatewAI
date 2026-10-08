package io.github.yourimartin.gatewai.infrastructure.cache;

import static io.github.yourimartin.gatewai.infrastructure.cache.SemanticCacheScopeTest.REGISTRY;
import static io.github.yourimartin.gatewai.infrastructure.cache.SemanticCacheScopeTest.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.InMemoryConversationAffinityStore;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationAffinity;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationOpening;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import reactor.core.publisher.Flux;

/**
 * The cache's part in conversation-sticky routing (ADR 0015, v4 A.3): the router
 * never sees a hit, so a conversation that opens on a cached answer is recorded
 * here, on the model the stored answer was routed to.
 */
class SemanticCacheConversationTest {

  private static final List<Message> FIRST_TURN =
      List.of(new UserMessage("What is a B-tree?"));

  private SimpleVectorStore store;
  private InMemoryConversationAffinityStore conversations;
  private SemanticCacheAdvisor advisor;

  @BeforeEach
  void setUp() {
    store = SimpleVectorStore.builder(
        new SemanticCacheScopeTest.WindowedEmbeddingModel()).build();
    conversations = new InMemoryConversationAffinityStore();
    advisor = new SemanticCacheAdvisor(store, new SemanticCacheProperties(),
        new CacheDecisionTracer(NO_DECISIONS, NO_METRICS), CalibrationFixtures.none(0.92),
        REGISTRY, text -> true, conversations);
  }

  @Test
  void aRoutedAnswerIsStoredWithTheRegistryModelItWasRoutedTo() {
    call(request(FIRST_TURN), new RoutedChain("model-a"));

    Document stored = store.similaritySearch(SearchRequest.builder()
        .query("What is a B-tree?").topK(1).build()).getFirst();
    assertEquals("model-a", stored.getMetadata().get(SemanticCacheAdvisor.ROUTED_MODEL_KEY));
  }

  @Test
  void aCachedFirstTurnStartsAConversationOnTheStoredModel() {
    call(request(FIRST_TURN), new RoutedChain("model-a"));
    assertTrue(conversations.records().isEmpty(), "the miss is the router's to record");

    call(request(FIRST_TURN), new RoutedChain("model-a"));

    ConversationAffinity recorded = conversations.find(opening()).orElseThrow();
    assertEquals("model-a", recorded.modelId());
    assertEquals(ModelTier.LOCAL, recorded.tier());
  }

  @Test
  void aStreamedHitStartsTheConversationToo() {
    stream(request(FIRST_TURN), new RoutedChain("model-a"));
    stream(request(FIRST_TURN), new RoutedChain("model-a"));

    assertEquals("model-a", conversations.find(opening()).orElseThrow().modelId());
  }

  @Test
  void anEntryWithNoRoutedModelStartsNothing() {
    // A pinned answer, or one stored before ADR 0015.
    call(request(FIRST_TURN), new SemanticCacheScopeTest.CountingChain());
    call(request(FIRST_TURN), new SemanticCacheScopeTest.CountingChain());

    assertTrue(conversations.records().isEmpty());
  }

  @Test
  void aHitInsideAConversationStartsNothing() {
    List<Message> followUp = List.of(new UserMessage("What is a B-tree?"),
        new AssistantMessage("A balanced tree."), new UserMessage("Why balanced?"));
    call(request(followUp), new RoutedChain("model-a"));
    call(request(followUp), new RoutedChain("model-a"));

    assertTrue(conversations.records().isEmpty(), "only a first turn opens a conversation");
  }

  private void call(ChatClientRequest request, SemanticCacheScopeTest.CountingChain chain) {
    ScopedValue.where(RequestContext.CURRENT, new RequestContext("tenant-1", "trace"))
        .run(() -> advisor.adviseCall(request, chain));
  }

  private void stream(ChatClientRequest request, SemanticCacheScopeTest.CountingChain chain) {
    ScopedValue.where(RequestContext.CURRENT, new RequestContext("tenant-1", "trace"))
        .run(() -> advisor.adviseStream(request, chain).collectList().block());
  }

  /** The opening the cached answer gives the first turn. */
  private static String opening() {
    return ConversationOpening.ofFirstTurn(
        List.of(new ConversationOpening.Turn("user", "What is a B-tree?")), "An answer.")
        .orElseThrow().fingerprint();
  }

  /** The chain as the router leaves it: every response names the routed model. */
  private static final class RoutedChain extends SemanticCacheScopeTest.CountingChain {

    private final String routedModel;

    RoutedChain(String routedModel) {
      this.routedModel = routedModel;
    }

    @Override
    public ChatClientResponse nextCall(ChatClientRequest request) {
      return stamp(super.nextCall(request));
    }

    @Override
    public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
      return super.nextStream(request).map(this::stamp);
    }

    private ChatClientResponse stamp(ChatClientResponse response) {
      ChatResponse stamped = ChatResponse.builder().from(response.chatResponse())
          .metadata(LlmResponse.ROUTED_MODEL_METADATA_KEY, routedModel).build();
      return ChatClientResponse.builder().chatResponse(stamped)
          .context(response.context()).build();
    }
  }

  private static final DecisionRecorder NO_DECISIONS = new DecisionRecorder() {
    @Override
    public void record(RoutingDecision decision) {
    }

    @Override
    public void record(CacheDecision decision) {
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
      return 0;
    }
  };

  private static final DecisionMetricsRecorder NO_METRICS = new DecisionMetricsRecorder() {
    @Override
    public void record(RoutingDecision decision) {
    }

    @Override
    public void record(CacheDecision decision) {
    }
  };
}
