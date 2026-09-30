package io.github.yourimartin.gatewai.infrastructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.carbon.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecisionReason;
import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import reactor.core.publisher.Flux;

/**
 * The semantic cache against the conversation collisions v4 A.1 measured, one
 * test per category, on the call path and on the stream path (v4 A.2).
 *
 * <p>The store is a real {@link SimpleVectorStore}, which honours the advisor's
 * filter expressions, and the embedding is a deterministic fake that, like the
 * production tokenizer, only sees the opening of a text. So an identical last
 * turn is an identical vector here exactly as it is in production — the thing
 * under test is what the advisor does with the context around it.
 */
class SemanticCacheScopeTest {

  /** Characters the fake embedding sees: the stand-in for the 128-token window. */
  private static final int WINDOW = 60;

  private static final String CLIENT = "tenant-1";

  private SimpleVectorStore store;
  private List<CacheDecision> decisions;
  private SemanticCacheAdvisor advisor;

  @BeforeEach
  void setUp() {
    store = SimpleVectorStore.builder(new WindowedEmbeddingModel()).build();
    decisions = new ArrayList<>();
    advisor = newAdvisor();
  }

  private SemanticCacheAdvisor newAdvisor() {
    CacheDecisionTracer tracer = new CacheDecisionTracer(recording(decisions), NO_METRICS);
    return new SemanticCacheAdvisor(store, new SemanticCacheProperties(), tracer,
        CalibrationFixtures.none(0.92), REGISTRY, text -> text.length() <= WINDOW);
  }

  // ---- The four A.1 collision categories: never served across contexts ----

  static final List<Message> FOLLOW_UP_STORED = List.of(
      new UserMessage("How do I read a file line by line in Python?"),
      new AssistantMessage("Iterate over the file object inside a with block."),
      new UserMessage("Give me an example in Java"));
  static final List<Message> FOLLOW_UP_INCOMING = List.of(
      new UserMessage("How do I sort a list of objects by a field in Python?"),
      new AssistantMessage("Use sorted() with a key function."),
      new UserMessage("Give me an example in Java"));

  static final List<Message> SYSTEM_STORED = List.of(
      new SystemMessage("Always answer in English."),
      new UserMessage("Summarise the causes of the French Revolution."));
  static final List<Message> SYSTEM_INCOMING = List.of(
      new SystemMessage("Always answer in Spanish."),
      new UserMessage("Summarise the causes of the French Revolution."));

  static final List<Message> END_USER_STORED = List.of(
      new SystemMessage("Support assistant. Customer: Alice. Order 48213 shipped."),
      new UserMessage("Where is my order?"));
  static final List<Message> END_USER_INCOMING = List.of(
      new SystemMessage("Support assistant. Customer: Bruno. Order 51877 not shipped."),
      new UserMessage("Where is my order?"));

  static final String TEMPLATE = "Answer using only the context below, citing the section. ";
  static final List<Message> TEMPLATE_STORED = List.of(
      new UserMessage(TEMPLATE + "Context: leave is 2.5 days a month. Question: how much leave?"));
  static final List<Message> TEMPLATE_INCOMING = List.of(
      new UserMessage(TEMPLATE + "Context: remote work is 2 days a week. Question: remote days?"));

  @Nested
  class CallPath {

    @Test
    void followUpCollisionIsNotServed() {
      assertNotServed(FOLLOW_UP_STORED, FOLLOW_UP_INCOMING);
    }

    @Test
    void systemPromptCollisionIsNotServed() {
      assertNotServed(SYSTEM_STORED, SYSTEM_INCOMING);
    }

    @Test
    void endUserCollisionIsNotServed() {
      assertNotServed(END_USER_STORED, END_USER_INCOMING);
    }

    @Test
    void templatePrefixCollisionIsNotServed() {
      assertNotServed(TEMPLATE_STORED, TEMPLATE_INCOMING);
    }

    @Test
    void theSameContextIsStillServed() {
      call(FOLLOW_UP_STORED);
      assertEquals(0, call(FOLLOW_UP_STORED), "an identical conversation must hit");
    }

    private void assertNotServed(List<Message> stored, List<Message> incoming) {
      assertEquals(1, call(stored), "the stored conversation must reach the model");
      assertEquals(1, call(incoming), "served across conversation contexts");
    }
  }

  @Nested
  class StreamPath {

    @Test
    void followUpCollisionIsNotServed() {
      assertNotServed(FOLLOW_UP_STORED, FOLLOW_UP_INCOMING);
    }

    @Test
    void systemPromptCollisionIsNotServed() {
      assertNotServed(SYSTEM_STORED, SYSTEM_INCOMING);
    }

    @Test
    void endUserCollisionIsNotServed() {
      assertNotServed(END_USER_STORED, END_USER_INCOMING);
    }

    @Test
    void templatePrefixCollisionIsNotServed() {
      assertNotServed(TEMPLATE_STORED, TEMPLATE_INCOMING);
    }

    @Test
    void theSameContextIsStillServed() {
      stream(FOLLOW_UP_STORED);
      assertEquals(0, stream(FOLLOW_UP_STORED), "an identical conversation must hit");
    }

    private void assertNotServed(List<Message> stored, List<Message> incoming) {
      assertEquals(1, stream(stored), "the stored conversation must reach the model");
      assertEquals(1, stream(incoming), "served across conversation contexts");
    }
  }

  // ---- The rest of the scope rules (ADR 0014) ----

  @Nested
  class ScopeRules {

    private final List<Message> question = List.of(new UserMessage("What is a B-tree?"));

    @Test
    void endUsersOfOneApiKeyNeverShareAnAnswer() {
      call(request(question, null, Map.of(LlmRequest.END_USER_CONTEXT_KEY, "user-1")));
      assertEquals(1,
          call(request(question, null, Map.of(LlmRequest.END_USER_CONTEXT_KEY, "user-2"))),
          "another end user was served user-1's answer");
      assertEquals(0,
          call(request(question, null, Map.of(LlmRequest.END_USER_CONTEXT_KEY, "user-1"))),
          "the same end user must still hit");
    }

    @Test
    void stopSequencesArePartOfTheScope() {
      call(request(question, ChatOptions.builder().stopSequences(List.of("\n")).build(),
          Map.of()));
      assertEquals(1, call(question), "an answer generated with a stop sequence was served "
          + "to a request without it");
    }

    @Test
    void aRegisteredModelIsAPinButAnUnregisteredNameIsRouted() {
      call(request(question, ChatOptions.builder().model("model-a").build(), Map.of()));
      assertEquals(1, call(request(question, ChatOptions.builder().model("gpt-x").build(),
          Map.of())), "a pinned model's answer was served to a routed request");
      assertEquals(0, call(request(question, ChatOptions.builder().model("gpt-y").build(),
          Map.of())), "two unregistered names are both routed, so they share a scope");
    }

    @Test
    void aLongHistoryBypassesTheCache() {
      List<Message> secondFollowUp = List.of(
          new UserMessage("What is a B-tree?"), new AssistantMessage("A balanced tree."),
          new UserMessage("And a B+ tree?"), new AssistantMessage("Leaves are linked."),
          new UserMessage("Which one does PostgreSQL use?"));
      call(secondFollowUp);
      assertEquals(1, call(secondFollowUp), "a bypassed request was served");
      assertEquals(CacheOutcome.BYPASS, decisions.getLast().outcome());
      assertEquals(CacheDecisionReason.HISTORY_TOO_LONG, decisions.getLast().reason());
    }

    @Test
    void aLongTurnIsMatchedExactly() {
      call(TEMPLATE_STORED);
      assertEquals(0, call(TEMPLATE_STORED), "an identical long turn must still hit");
      assertEquals(CacheOutcome.HIT, decisions.getLast().outcome());
      assertEquals(CacheDecisionReason.EXACT_MATCH_ONLY, decisions.getLast().reason());
    }

    @Test
    void onlyAnAnswerThatEndedNormallyIsStored() {
      CountingChain truncated = new CountingChain();
      truncated.finishReason = "length";
      call(request(question), truncated);
      assertEquals(1, call(question), "an answer cut by a token limit was stored");

      CountingChain anthropic = new CountingChain();
      anthropic.finishReason = "end_turn";
      call(request(question), anthropic);
      assertEquals(0, call(question), "end_turn is a normal end and must be stored");
    }

    @Test
    void aStoredAnswerLongerThanMaxTokensIsNotServed() {
      call(question);
      assertEquals(1, call(request(question, ChatOptions.builder().maxTokens(10).build(),
          Map.of())), "a 50-token answer was served to a 10-token request");
      assertEquals(CacheDecisionReason.MAX_TOKENS, decisions.getLast().reason());
    }

    @Test
    void anEntryWrittenBeforeScopesNeverMatches() {
      store.add(List.of(new Document("What is a B-tree?", Map.of(
          SemanticCacheAdvisor.CACHE_RESPONSE_KEY, "A legacy answer.",
          SemanticCacheAdvisor.CLIENT_ID_KEY, CLIENT))));
      assertEquals(1, call(question), "a legacy entry with no scope was served");
    }

    @Test
    void theDecisionRecordsTheScopeAsAHash() {
      call(SYSTEM_STORED);
      String scope = decisions.getLast().cacheScope();
      assertEquals(64, scope.length());
      assertEquals(-1, scope.indexOf("English"), "the scope must be a hash, never text");
    }

    @Test
    void theResponseCarriesTheCacheOutcome() {
      assertEquals("MISS", outcomeOf(request(question)));
      assertEquals("HIT", outcomeOf(request(question)));
      assertEquals("BYPASS", outcomeOf(request(List.of(new UserMessage(" ")))));
    }

    private String outcomeOf(ChatClientRequest request) {
      ChatClientResponse response = ScopedValue.where(RequestContext.CURRENT,
              new RequestContext(CLIENT, "trace"))
          .call(() -> advisor.adviseCall(request, new CountingChain()));
      return response.chatResponse().getMetadata().get(LlmResponse.CACHE_OUTCOME_METADATA_KEY);
    }
  }

  // ---- Helpers ----

  /** Runs one call-path request and returns how many times the model was called. */
  int call(List<Message> messages) {
    return call(request(messages));
  }

  int call(ChatClientRequest request) {
    return call(request, new CountingChain());
  }

  int call(ChatClientRequest request, CountingChain chain) {
    ScopedValue.where(RequestContext.CURRENT, new RequestContext(CLIENT, "trace"))
        .run(() -> advisor.adviseCall(request, chain));
    return chain.calls.get();
  }

  /** Runs one stream-path request to completion and returns the model call count. */
  int stream(List<Message> messages) {
    return stream(request(messages));
  }

  int stream(ChatClientRequest request) {
    CountingChain chain = new CountingChain();
    ScopedValue.where(RequestContext.CURRENT, new RequestContext(CLIENT, "trace"))
        .run(() -> advisor.adviseStream(request, chain)
            .collectList().block());
    return chain.calls.get();
  }

  static ChatClientRequest request(List<Message> messages) {
    return ChatClientRequest.builder().prompt(new Prompt(messages)).context(Map.of()).build();
  }

  static ChatClientRequest request(List<Message> messages, ChatOptions options,
                                   Map<String, Object> context) {
    Prompt prompt = options == null ? new Prompt(messages) : new Prompt(messages, options);
    return ChatClientRequest.builder().prompt(prompt).context(context).build();
  }

  /** Knows one model, {@code model-a}: requesting it is a pin. */
  static final ModelRegistry REGISTRY = new ModelRegistry() {
    private final ModelDefinition modelA = new ModelDefinition("a", "provider", "model-a",
        0.0, EnergyProfile.NOT_ACCOUNTED, ModelTier.LOCAL);

    @Override
    public List<ModelDefinition> allModels() {
      return List.of(modelA);
    }

    @Override
    public Optional<ModelDefinition> findByKey(String key) {
      return Optional.empty();
    }

    @Override
    public Optional<ModelDefinition> findByModelId(String modelId) {
      return "model-a".equals(modelId) ? Optional.of(modelA) : Optional.empty();
    }

    @Override
    public List<ModelDefinition> findByTier(ModelTier tier) {
      return List.of();
    }
  };

  static ChatResponse answer(String text, String finishReason, int completionTokens) {
    Generation generation = new Generation(new AssistantMessage(text),
        ChatGenerationMetadata.builder().finishReason(finishReason).build());
    return new ChatResponse(List.of(generation), ChatResponseMetadata.builder()
        .model("model-a").usage(new DefaultUsage(20, completionTokens)).build());
  }

  /** A model at the end of both chains: answers "stop" and counts the calls. */
  static class CountingChain implements CallAdvisorChain, StreamAdvisorChain {

    final AtomicInteger calls = new AtomicInteger();
    String finishReason = "stop";
    int completionTokens = 50;

    @Override
    public ChatClientResponse nextCall(ChatClientRequest request) {
      calls.incrementAndGet();
      return ChatClientResponse.builder()
          .chatResponse(answer("An answer.", finishReason, completionTokens))
          .context(request.context()).build();
    }

    @Override
    public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
      calls.incrementAndGet();
      return Flux.just(
          ChatClientResponse.builder().chatResponse(answer("An ", null, 0))
              .context(request.context()).build(),
          ChatClientResponse.builder()
              .chatResponse(answer("answer.", finishReason, completionTokens))
              .context(request.context()).build());
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

  /**
   * Deterministic embedding that only sees the first {@link #WINDOW} characters,
   * as the real tokenizer only sees the first 128 tokens: identical openings are
   * identical vectors.
   */
  static final class WindowedEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 64;

    @Override
    public float[] embed(String text) {
      String seen = text.length() > WINDOW ? text.substring(0, WINDOW) : text;
      float[] vector = new float[DIMENSIONS];
      byte[] bytes = seen.getBytes(StandardCharsets.UTF_8);
      for (int i = 0; i < bytes.length; i++) {
        vector[(bytes[i] * 31 + i) & (DIMENSIONS - 1)] += 1;
      }
      return vector;
    }

    @Override
    public float[] embed(Document document) {
      return embed(document.getText());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      List<Embedding> embeddings = new ArrayList<>();
      for (int i = 0; i < request.getInstructions().size(); i++) {
        embeddings.add(new Embedding(embed(request.getInstructions().get(i)), i));
      }
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public int dimensions() {
      return DIMENSIONS;
    }
  }

  static DecisionRecorder recording(List<CacheDecision> decisions) {
    return new DecisionRecorder() {
      @Override
      public void record(RoutingDecision decision) {
        // The cache records no routing decision.
      }

      @Override
      public void record(CacheDecision decision) {
        decisions.add(decision);
      }

      @Override
      public int purgeOlderThan(java.time.Instant cutoff) {
        return 0;
      }
    };
  }

  static final DecisionMetricsRecorder NO_METRICS = new DecisionMetricsRecorder() {
    @Override
    public void record(RoutingDecision decision) {
      // Not under test.
    }

    @Override
    public void record(CacheDecision decision) {
      // Not under test.
    }
  };
}
