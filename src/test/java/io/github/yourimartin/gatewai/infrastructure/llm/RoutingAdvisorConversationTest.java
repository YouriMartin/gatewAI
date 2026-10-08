package io.github.yourimartin.gatewai.infrastructure.llm;

import static io.github.yourimartin.gatewai.infrastructure.llm.ClassificationOutcomeFixtures.outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.InMemoryConversationAffinityStore;
import io.github.yourimartin.gatewai.domain.model.carbon.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationAffinity;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationOpening;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationRouting;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;
import io.github.yourimartin.gatewai.domain.port.out.ConversationAffinityStore;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * Conversation-sticky routing through the real advisor (ADR 0015, v4 A.3): one
 * conversation followed turn by turn, with the history resent the way a client
 * resends it.
 */
class RoutingAdvisorConversationTest {

  private static final ModelDefinition LOCAL = model("local-1", ModelTier.LOCAL);
  private static final ModelDefinition ENTRY = model("entry-1", ModelTier.CLOUD_ENTRY);
  private static final ModelDefinition PREMIUM = model("premium-1", ModelTier.CLOUD_PREMIUM);
  private static final ModelDefinition PREMIUM_2 = model("premium-2", ModelTier.CLOUD_PREMIUM);

  private static final String SYSTEM = "You are a senior engineer.";
  private static final String HARD = "Design a sharded event store with exactly-once delivery";
  private static final String EASY = "thanks";
  private static final String ANSWER = "An answer.";

  /** Classifies on a fixed table: the conversation, not the classifier, is under test. */
  private static final Map<String, ModelTier> TIERS = Map.of(
      HARD, ModelTier.CLOUD_PREMIUM,
      EASY, ModelTier.LOCAL,
      "hello", ModelTier.LOCAL,
      "and one more thing", ModelTier.LOCAL);

  private InMemoryConversationAffinityStore store;
  private List<RoutingDecision> decisions;
  private List<String> classified;
  private RoutingAdvisor advisor;

  @BeforeEach
  void setUp() {
    store = new InMemoryConversationAffinityStore();
    decisions = new ArrayList<>();
    classified = new ArrayList<>();
    advisor = advisor(store);
  }

  private RoutingAdvisor advisor(ConversationAffinityStore conversations) {
    ComplexityClassifier classifier = text -> {
      classified.add(text);
      return outcome(TIERS.getOrDefault(text, ModelTier.LOCAL));
    };
    return new RoutingAdvisor(classifier, REGISTRY, recording(decisions),
        mock(DecisionMetricsRecorder.class), mock(RoutingConfigVersionTracker.class),
        CalibrationFixtures.none(0.60), new ClassifierProperties(),
        new ConversationStickiness(conversations, REGISTRY, classifier));
  }

  @Test
  void aPremiumConversationsShortFollowUpStaysOnTheSameModel() {
    assertEquals("premium-1", call(SYSTEM, HARD));

    assertEquals("premium-1", call(SYSTEM, HARD, ANSWER, EASY),
        "a short follow-up in a hard conversation dropped to the local tier");
    RoutingDecision decision = decisions.getLast();
    assertEquals(ConversationRouting.STICKY, decision.conversationRouting());
    assertEquals(ModelTier.LOCAL, decision.classifiedTier(),
        "the trace must keep what the turn alone was worth");
    assertEquals(ModelTier.CLOUD_PREMIUM, decision.chosenTier());
  }

  @Test
  void anUnchangedTierKeepsTheRecordedModelNotJustTheTier() {
    // The record says premium-2; the tier's first model is premium-1. Moving
    // between them would forfeit the provider's prompt cache on the history.
    String fingerprint = opening(SYSTEM, HARD, ANSWER);
    store.record(fingerprint, "premium-2", ModelTier.CLOUD_PREMIUM);

    assertEquals("premium-2", call(SYSTEM, HARD, ANSWER, EASY));
    assertEquals("premium-2", call(SYSTEM, HARD, ANSWER, HARD));
  }

  @Test
  void aLocalConversationUpgradesOnAHardFollowUpAndNeverComesBackDown() {
    assertEquals("local-1", call(null, "hello"));

    assertEquals("premium-1", call(null, "hello", ANSWER, HARD));
    assertEquals(ConversationRouting.UPGRADED, decisions.getLast().conversationRouting());

    assertEquals("premium-1", call(null, "hello", ANSWER, HARD, ANSWER, EASY));
    assertEquals(ConversationRouting.STICKY, decisions.getLast().conversationRouting());
    assertEquals(ModelTier.CLOUD_PREMIUM,
        store.find(opening(null, "hello", ANSWER)).orElseThrow().tier());
  }

  @Test
  void aConversationWithNoRecordIsFlooredOnItsFirstTurnAndRecordedFromThere() {
    // Started before A.3, or past retention: nothing recorded.
    assertEquals("premium-1", call(SYSTEM, HARD, ANSWER, EASY));
    RoutingDecision decision = decisions.getLast();
    assertEquals(ConversationRouting.FIRST_TURN_FLOOR, decision.conversationRouting());
    assertEquals(ModelTier.LOCAL, decision.classifiedTier());
    assertEquals(List.of(EASY, HARD), classified, "the first turn was not classified");

    // From now on it is a recorded conversation: no second classification, and
    // a later easy turn cannot fall back below the hard one.
    classified.clear();
    assertEquals("premium-1",
        call(SYSTEM, HARD, ANSWER, EASY, ANSWER, "and one more thing"));
    assertEquals(ConversationRouting.STICKY, decisions.getLast().conversationRouting());
    assertEquals(List.of("and one more thing"), classified);
  }

  @Test
  void aFirstTurnIsRecordedWithItsAnswerAndTracedWithoutConversationFields() {
    call(SYSTEM, HARD);

    ConversationAffinity recorded = store.find(opening(SYSTEM, HARD, ANSWER)).orElseThrow();
    assertEquals("premium-1", recorded.modelId());
    RoutingDecision decision = decisions.getLast();
    assertNull(decision.conversationRouting());
    assertNull(decision.classifiedTier());
    assertNull(decision.conversationFingerprint());
  }

  @Test
  void everyTurnOfAConversationCarriesTheSameFingerprint() {
    call(SYSTEM, HARD, ANSWER, EASY);
    call(SYSTEM, HARD, ANSWER, EASY, ANSWER, "and one more thing");

    assertEquals(opening(SYSTEM, HARD, ANSWER), decisions.get(0).conversationFingerprint());
    assertEquals(decisions.get(0).conversationFingerprint(),
        decisions.get(1).conversationFingerprint());
  }

  @Test
  void pinnedRequestsAreUntouchedAndNeverRecorded() {
    ChatOptions pinned = ChatOptions.builder().model("local-1").build();

    assertEquals("local-1", call(request(pinned, SYSTEM, HARD)));
    assertEquals("local-1", call(request(pinned, SYSTEM, HARD, ANSWER, HARD)));

    assertTrue(store.records().isEmpty(), "a pinned request was recorded");
    decisions.forEach(decision -> assertNull(decision.conversationRouting()));
  }

  @Test
  void streamingRecordsOnCompletionWithTheAggregatedAnswer() {
    Flux<ChatClientResponse> stream =
        advisor.adviseStream(request(null, SYSTEM, HARD), new AnsweringChain());
    assertTrue(store.records().isEmpty(), "recorded before the answer was known");

    stream.collectList().block();

    // The chain streams "An " then "answer.": the client resends the whole.
    assertEquals("premium-1",
        store.find(opening(SYSTEM, HARD, ANSWER)).orElseThrow().modelId());
  }

  @Test
  void streamingFollowsTheConversationLikeTheCallPath() {
    call(SYSTEM, HARD);
    AnsweringChain chain = new AnsweringChain();

    advisor.adviseStream(request(null, SYSTEM, HARD, ANSWER, EASY), chain)
        .collectList().block();

    assertEquals("premium-1", chain.model);
    assertEquals(ConversationRouting.STICKY, decisions.getLast().conversationRouting());
  }

  @Test
  void theResponseCarriesTheRoutedRegistryModel() {
    ChatClientResponse response = advisor.adviseCall(request(null, SYSTEM, HARD),
        new AnsweringChain());
    assertEquals("premium-1", response.chatResponse().getMetadata()
        .get(LlmResponse.ROUTED_MODEL_METADATA_KEY));

    List<ChatClientResponse> chunks = advisor.adviseStream(request(null, SYSTEM, HARD),
        new AnsweringChain()).collectList().block();
    chunks.forEach(chunk -> assertEquals("premium-1",
        chunk.chatResponse().getMetadata().get(LlmResponse.ROUTED_MODEL_METADATA_KEY)));
  }

  @Test
  void anUnreachableStoreDegradesToTheFirstTurnFloorNotToAnError() {
    advisor = advisor(new ConversationAffinityStore() {
      @Override
      public Optional<ConversationAffinity> find(String fingerprint) {
        throw new IllegalStateException("database down");
      }

      @Override
      public void record(String fingerprint, String modelId, ModelTier tier) {
        throw new IllegalStateException("database down");
      }

      @Override
      public int purgeLastSeenBefore(Instant cutoff) {
        return 0;
      }
    });

    assertEquals("premium-1", call(SYSTEM, HARD));
    assertEquals("premium-1", call(SYSTEM, HARD, ANSWER, EASY));
    assertEquals(ConversationRouting.FIRST_TURN_FLOOR, decisions.getLast().conversationRouting());
  }

  // ---- Helpers ----

  /** The model the request was sent to. */
  private String call(String system, String... turns) {
    return call(request(null, system, turns));
  }

  private String call(ChatClientRequest request) {
    AnsweringChain chain = new AnsweringChain();
    advisor.adviseCall(request, chain);
    return chain.model;
  }

  /** Alternating user and assistant turns, after an optional system message. */
  private static ChatClientRequest request(ChatOptions options, String system,
                                           String... turns) {
    List<Message> messages = new ArrayList<>();
    if (system != null) {
      messages.add(new SystemMessage(system));
    }
    for (int i = 0; i < turns.length; i++) {
      messages.add(i % 2 == 0 ? new UserMessage(turns[i]) : new AssistantMessage(turns[i]));
    }
    ChatOptions requested = options == null
        ? ChatOptions.builder().model("gatewai-auto").build() : options;
    return ChatClientRequest.builder().prompt(new Prompt(messages, requested))
        .context(Map.of()).build();
  }

  private static String opening(String system, String firstUser, String firstAnswer) {
    List<ConversationOpening.Turn> turns = new ArrayList<>();
    if (system != null) {
      turns.add(new ConversationOpening.Turn("system", system));
    }
    turns.add(new ConversationOpening.Turn("user", firstUser));
    return ConversationOpening.ofFirstTurn(turns, firstAnswer).orElseThrow().fingerprint();
  }

  private static ModelDefinition model(String id, ModelTier tier) {
    return new ModelDefinition(id, "provider", id, 0.0, EnergyProfile.NOT_ACCOUNTED, tier);
  }

  private static DecisionRecorder recording(List<RoutingDecision> into) {
    return new DecisionRecorder() {
      @Override
      public void record(RoutingDecision decision) {
        into.add(decision);
      }

      @Override
      public void record(CacheDecision decision) {
      }

      @Override
      public int purgeOlderThan(Instant cutoff) {
        return 0;
      }
    };
  }

  private static final ModelRegistry REGISTRY = new ModelRegistry() {
    private final List<ModelDefinition> models = List.of(LOCAL, ENTRY, PREMIUM, PREMIUM_2);

    @Override
    public List<ModelDefinition> allModels() {
      return models;
    }

    @Override
    public Optional<ModelDefinition> findByKey(String key) {
      return findByModelId(key);
    }

    @Override
    public Optional<ModelDefinition> findByModelId(String modelId) {
      return models.stream().filter(m -> m.modelId().equals(modelId)).findFirst();
    }

    @Override
    public List<ModelDefinition> findByTier(ModelTier tier) {
      return models.stream().filter(m -> m.tier() == tier).toList();
    }
  };

  /** The end of the chain: answers "An answer." and remembers the model it was sent. */
  private static final class AnsweringChain implements CallAdvisorChain, StreamAdvisorChain {

    String model;

    @Override
    public ChatClientResponse nextCall(ChatClientRequest request) {
      model = request.prompt().getOptions().getModel();
      return response(ANSWER, request);
    }

    @Override
    public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
      model = request.prompt().getOptions().getModel();
      return Flux.just(response("An ", request), response("answer.", request));
    }

    private ChatClientResponse response(String text, ChatClientRequest request) {
      return ChatClientResponse.builder()
          .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
              ChatResponseMetadata.builder().model(model + "-2026-01-01").build()))
          .context(request.context()).build();
    }

    @Override
    public List<CallAdvisor> getCallAdvisors() {
      return List.of();
    }

    @Override
    public List<StreamAdvisor> getStreamAdvisors() {
      return List.of();
    }

    @Override
    public CallAdvisorChain copy(CallAdvisor after) {
      return this;
    }

    @Override
    public StreamAdvisorChain copy(StreamAdvisor after) {
      return this;
    }
  }
}
