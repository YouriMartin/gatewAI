package io.github.yourimartin.gatewai.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.EmbeddingWindow;
import io.github.yourimartin.gatewai.infrastructure.cache.EvalCacheAdvisorFactory;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;

/**
 * Measures what the semantic cache does with conversations (v4 batch A.1).
 *
 * <p>Every case runs the <b>real</b> {@code SemanticCacheAdvisor} twice, on a
 * fresh in-memory store: once on {@code stored}, which misses, reaches a stub
 * model and is written to the cache exactly as production writes it; then on
 * {@code incoming}, under the same client. The case counts as <b>served</b> when
 * the second call never reached the model. Nothing about the lookup is
 * re-implemented here — which text is embedded, which filter applies, which
 * candidate is accepted — so the numbers move when, and only when, the advisor
 * does.
 *
 * <p>Two rates, kept apart because they are not the same kind of error:
 * <ul>
 *   <li>{@code cross_context_hit_rate} — served on {@code NO} cases, an answer
 *       from another conversation, persona or end user. Lower is better; every
 *       unit of it is a wrong answer delivered with full confidence.</li>
 *   <li>{@code same_context_hit_rate} — served on {@code YES} cases. Higher is
 *       better; a miss here only costs a model call.</li>
 * </ul>
 *
 * <p>Runs at the fixed threshold with no calibration applied, like the
 * fixed-threshold cache metrics: the defect measured here is not a threshold
 * problem, and a calibration fitted on single-turn pairs would not make it one.
 */
final class ConversationCacheEvaluator {

  /** Both requests of a case come from one API key, as the collisions do in production. */
  static final String CLIENT_ID = "eval-client";

  private ConversationCacheEvaluator() {
  }

  static Result evaluate(List<ConversationCase> cases, EmbeddingModel embeddings,
                         EmbeddingWindow window, double threshold) {
    List<Outcome> outcomes = new ArrayList<>();
    for (ConversationCase conversation : cases) {
      outcomes.add(run(conversation, embeddings, window, threshold));
    }

    Map<String, TagScore> byTag = new LinkedHashMap<>();
    TagScore overall = TagScore.EMPTY;
    for (Outcome outcome : outcomes) {
      TagScore one = TagScore.of(outcome);
      overall = overall.plus(one);
      for (String tag : outcome.conversation().tags()) {
        byTag.merge(tag, one, TagScore::plus);
      }
    }
    return new Result(threshold, List.copyOf(outcomes), byTag, overall);
  }

  private static Outcome run(ConversationCase conversation, EmbeddingModel embeddings,
                             EmbeddingWindow window, double threshold) {
    List<CacheDecision> decisions = new ArrayList<>();
    CallAdvisor cache = EvalCacheAdvisorFactory.semanticCache(
        SimpleVectorStore.builder(embeddings).build(),
        CalibrationFixtures.none(threshold),
        collecting(decisions),
        window);

    AnsweringChain storing = new AnsweringChain("Answer to " + conversation.id() + " (stored)");
    within(conversation.id() + "-stored",
        () -> cache.adviseCall(request(conversation.stored()), storing));
    if (storing.calls() != 1) {
      throw new IllegalStateException(conversation.id()
          + ": the stored request did not reach the model on an empty cache");
    }

    AnsweringChain answering = new AnsweringChain("Answer to " + conversation.id());
    String incomingId = conversation.id() + "-incoming";
    within(incomingId, () -> cache.adviseCall(request(conversation.incoming()), answering));

    CacheDecision decision = decisions.stream()
        .filter(d -> incomingId.equals(d.correlationId()))
        .reduce((first, second) -> second)
        .orElseThrow(() -> new IllegalStateException(
            conversation.id() + ": the incoming lookup left no cache decision"));
    if (decision.outcome() == CacheOutcome.ERROR) {
      // The advisor turns a failed lookup into a miss, which is right in
      // production and would be a silent false negative here.
      throw new IllegalStateException(conversation.id()
          + ": the cache lookup failed — the conversation fixtures are stale. Re-record: "
          + EvalPaths.RECORD_COMMAND);
    }
    return new Outcome(conversation, answering.calls() == 0, decision.outcome(),
        decision.similarityScore());
  }

  private static void within(String correlationId, Runnable call) {
    ScopedValue.where(RequestContext.CURRENT, new RequestContext(CLIENT_ID, correlationId))
        .run(call);
  }

  /**
   * The request as the chain receives it. Roles map as
   * {@code SpringAiLlmClient.toSpringMessage} maps them; the dataset only uses
   * {@code system}, {@code user} and {@code assistant}, where that mapping is
   * unambiguous.
   */
  private static ChatClientRequest request(List<ConversationCase.Turn> turns) {
    List<Message> messages = turns.stream()
        .map(turn -> switch (turn.role()) {
          case "system" -> (Message) new SystemMessage(turn.content());
          case "assistant" -> new AssistantMessage(turn.content());
          default -> new UserMessage(turn.content());
        })
        .toList();
    return ChatClientRequest.builder().prompt(new Prompt(messages)).context(Map.of()).build();
  }

  private static DecisionRecorder collecting(List<CacheDecision> decisions) {
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
      public int purgeOlderThan(Instant cutoff) {
        return 0;
      }
    };
  }

  /** The end of the chain: a model that always answers, and counts how often it was asked. */
  private static final class AnsweringChain implements CallAdvisorChain {

    private final String answer;
    private int calls;

    private AnsweringChain(String answer) {
      this.answer = answer;
    }

    int calls() {
      return calls;
    }

    @Override
    public ChatClientResponse nextCall(ChatClientRequest request) {
      calls++;
      Generation generation = new Generation(new AssistantMessage(answer),
          ChatGenerationMetadata.builder().finishReason("stop").build());
      ChatResponse response = new ChatResponse(List.of(generation),
          ChatResponseMetadata.builder()
              .model("eval-model")
              .usage(new DefaultUsage(100, 50))
              .build());
      return ChatClientResponse.builder().chatResponse(response).context(request.context())
          .build();
    }

    @Override
    public List<CallAdvisor> getCallAdvisors() {
      return List.of();
    }

    @Override
    public CallAdvisorChain copy(CallAdvisor after) {
      return this;
    }
  }

  /**
   * What happened to one case.
   *
   * @param served     true when the incoming request was answered from the cache
   * @param decision   what the cache traced for the incoming request — a
   *                   {@code BYPASS} refuses without comparing anything
   * @param similarity the best candidate's similarity, as the cache traced it
   */
  record Outcome(ConversationCase conversation, boolean served, CacheOutcome decision,
                 double similarity) {

    boolean wrong() {
      return served != conversation.servable();
    }
  }

  /** Counts for one slice, split by judgment. */
  record TagScore(int noCases, int noServed, int yesCases, int yesServed) {

    static final TagScore EMPTY = new TagScore(0, 0, 0, 0);

    static TagScore of(Outcome outcome) {
      int served = outcome.served() ? 1 : 0;
      return outcome.conversation().servable()
          ? new TagScore(0, 0, 1, served)
          : new TagScore(1, served, 0, 0);
    }

    TagScore plus(TagScore other) {
      return new TagScore(noCases + other.noCases, noServed + other.noServed,
          yesCases + other.yesCases, yesServed + other.yesServed);
    }

    /** Served on NO cases, or null when the slice has none. */
    Double crossContextHitRate() {
      return noCases == 0 ? null : (double) noServed / noCases;
    }

    /** Served on YES cases, or null when the slice has none. */
    Double sameContextHitRate() {
      return yesCases == 0 ? null : (double) yesServed / yesCases;
    }
  }

  /**
   * The whole run.
   *
   * @param threshold the similarity threshold the cache ran at
   * @param outcomes  every case, in dataset order
   * @param byTag     the two rates per tag
   * @param overall   the two rates over all cases
   */
  record Result(double threshold, List<Outcome> outcomes, Map<String, TagScore> byTag,
                TagScore overall) {
  }
}
