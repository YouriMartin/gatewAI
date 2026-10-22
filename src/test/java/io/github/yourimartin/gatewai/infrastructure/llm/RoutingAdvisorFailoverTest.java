package io.github.yourimartin.gatewai.infrastructure.llm;

import static io.github.yourimartin.gatewai.infrastructure.llm.ClassificationOutcomeFixtures.outcome;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.InMemoryConversationAffinityStore;
import io.github.yourimartin.gatewai.domain.model.carbon.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationOpening;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;
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
 * What the router does about failover (v4 B.5, ADR 0017): it allows a routed
 * request to fail over, not a pinned one, and a conversation follows the model
 * that actually answered.
 */
class RoutingAdvisorFailoverTest {

  private static final ModelDefinition LOCAL = model("local-1", ModelTier.LOCAL);
  private static final ModelDefinition ENTRY = model("entry-1", ModelTier.CLOUD_ENTRY);
  private static final ModelDefinition PREMIUM = model("premium-1", ModelTier.CLOUD_PREMIUM);
  private static final ModelDefinition PREMIUM_2 = model("premium-2", ModelTier.CLOUD_PREMIUM);
  private static final List<ModelDefinition> MODELS = List.of(LOCAL, ENTRY, PREMIUM, PREMIUM_2);

  private static final String SYSTEM = "You are a senior engineer.";
  private static final String HARD = "Design a sharded event store with exactly-once delivery";
  private static final String EASY = "thanks";
  private static final String ANSWER = "An answer.";

  private InMemoryConversationAffinityStore store;
  private FailoverProperties failover;
  private RoutingAdvisor advisor;

  @BeforeEach
  void setUp() {
    store = new InMemoryConversationAffinityStore();
    failover = new FailoverProperties();
    ComplexityClassifier classifier = text -> outcome(
        Map.of(HARD, ModelTier.CLOUD_PREMIUM).getOrDefault(text, ModelTier.LOCAL));
    advisor = new RoutingAdvisor(classifier, REGISTRY, mock(DecisionRecorder.class),
        mock(DecisionMetricsRecorder.class), mock(RoutingConfigVersionTracker.class),
        CalibrationFixtures.none(0.60), new ClassifierProperties(),
        new ConversationStickiness(store, REGISTRY, classifier), failover);
  }

  @Test
  void aRoutedRequestMayFailOver() {
    Chain chain = new Chain(null);

    advisor.adviseCall(request("auto", null, HARD), chain);

    assertThat(chain.model).isEqualTo("premium-1");
    assertThat(GatewaiChatOptions.failoverOf(chain.options)).isTrue();
  }

  @Test
  void aPinnedRequestMayNotByDefault() {
    Chain chain = new Chain(null);

    advisor.adviseCall(request("premium-1", null, HARD), chain);

    assertThat(chain.model).isEqualTo("premium-1");
    assertThat(GatewaiChatOptions.failoverOf(chain.options)).isFalse();
  }

  @Test
  void aPinnedRequestMayWhenTheOperatorSaysSo() {
    failover.setPinned(true);
    Chain chain = new Chain(null);

    advisor.adviseCall(request("premium-1", null, HARD), chain);

    assertThat(chain.model).isEqualTo("premium-1");
    assertThat(GatewaiChatOptions.failoverOf(chain.options)).isTrue();
  }

  @Test
  void aFirstTurnIsRecordedOnTheModelThatAnswered() {
    ChatClientResponse response = advisor.adviseCall(request("auto", SYSTEM, HARD),
        new Chain("premium-2"));

    assertThat(store.find(opening(SYSTEM, HARD, ANSWER)).orElseThrow().modelId())
        .isEqualTo("premium-2");
    assertThat((String) response.chatResponse().getMetadata()
        .get(LlmResponse.ROUTED_MODEL_METADATA_KEY)).isEqualTo("premium-2");
  }

  @Test
  void aStickyConversationFollowsTheModelThatAnsweredAndStaysOnItsTier() {
    String fingerprint = opening(SYSTEM, HARD, ANSWER);
    store.record(fingerprint, "premium-1", ModelTier.CLOUD_PREMIUM);

    advisor.adviseCall(request("auto", SYSTEM, HARD, ANSWER, EASY), new Chain("premium-2"));

    assertThat(store.find(fingerprint).orElseThrow().modelId()).isEqualTo("premium-2");
    assertThat(store.find(fingerprint).orElseThrow().tier()).isEqualTo(ModelTier.CLOUD_PREMIUM);
    Chain next = new Chain(null);
    advisor.adviseCall(request("auto", SYSTEM, HARD, ANSWER, EASY, ANSWER, EASY), next);
    assertThat(next.model).as("the next turn starts where the answer came from")
        .isEqualTo("premium-2");
  }

  @Test
  void aStreamedFirstTurnIsRecordedOnTheModelThatAnswered() {
    List<ChatClientResponse> chunks = advisor.adviseStream(request("auto", SYSTEM, HARD),
        new Chain("premium-2")).collectList().block();

    assertThat(store.find(opening(SYSTEM, HARD, ANSWER)).orElseThrow().modelId())
        .isEqualTo("premium-2");
    assertThat(chunks).allSatisfy(chunk -> assertThat((String) chunk.chatResponse()
        .getMetadata().get(LlmResponse.ROUTED_MODEL_METADATA_KEY)).isEqualTo("premium-2"));
  }

  @Test
  void anAnswerFromTheFirstTargetLeavesTheRecordAlone() {
    advisor.adviseCall(request("auto", SYSTEM, HARD), new Chain(null));

    assertThat(store.find(opening(SYSTEM, HARD, ANSWER)).orElseThrow().modelId())
        .isEqualTo("premium-1");
  }

  /** Alternating user and assistant turns, after an optional system message. */
  private static ChatClientRequest request(String model, String system, String... turns) {
    List<Message> messages = new ArrayList<>();
    if (system != null) {
      messages.add(new SystemMessage(system));
    }
    for (int i = 0; i < turns.length; i++) {
      messages.add(i % 2 == 0 ? new UserMessage(turns[i]) : new AssistantMessage(turns[i]));
    }
    return ChatClientRequest.builder()
        .prompt(new Prompt(messages, GatewaiChatOptions.builder().model(model).build()))
        .context(Map.of()).build();
  }

  private static String opening(String system, String firstUser, String firstAnswer) {
    List<ConversationOpening.Turn> turns = new ArrayList<>();
    turns.add(new ConversationOpening.Turn("system", system));
    turns.add(new ConversationOpening.Turn("user", firstUser));
    return ConversationOpening.ofFirstTurn(turns, firstAnswer).orElseThrow().fingerprint();
  }

  private static ModelDefinition model(String id, ModelTier tier) {
    return new ModelDefinition(id, "provider", id, ModelPricing.NONE, EnergyProfile.NOT_ACCOUNTED, tier);
  }

  private static final ModelRegistry REGISTRY = new ModelRegistry() {
    @Override
    public List<ModelDefinition> allModels() {
      return MODELS;
    }

    @Override
    public Optional<ModelDefinition> findByKey(String key) {
      return findByModelId(key);
    }

    @Override
    public Optional<ModelDefinition> findByModelId(String modelId) {
      return MODELS.stream().filter(m -> m.modelId().equals(modelId)).findFirst();
    }

    @Override
    public List<ModelDefinition> findByTier(ModelTier tier) {
      return MODELS.stream().filter(m -> m.tier() == tier).toList();
    }
  };

  /**
   * The end of the chain: remembers what it was sent and answers "An answer.",
   * naming {@code answeredBy} the way the egress does after a failover.
   */
  private static final class Chain implements CallAdvisorChain, StreamAdvisorChain {

    private final String answeredBy;
    String model;
    ChatOptions options;

    Chain(String answeredBy) {
      this.answeredBy = answeredBy;
    }

    @Override
    public ChatClientResponse nextCall(ChatClientRequest request) {
      options = request.prompt().getOptions();
      model = options.getModel();
      return response(ANSWER, request);
    }

    @Override
    public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
      options = request.prompt().getOptions();
      model = options.getModel();
      return Flux.just(response("An ", request), response("answer.", request));
    }

    private ChatClientResponse response(String text, ChatClientRequest request) {
      ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model("provider-name");
      if (answeredBy != null) {
        metadata.keyValue(DelegatingChatModel.ANSWERED_MODEL_METADATA_KEY, answeredBy);
      }
      return ChatClientResponse.builder()
          .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
              metadata.build()))
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
