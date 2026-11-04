package io.github.yourimartin.gatewai.application.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

import io.github.yourimartin.gatewai.domain.model.carbon.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.routing.Similarity;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeRubric;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeVerdict;
import io.github.yourimartin.gatewai.domain.model.shadow.WouldHitAssessment;
import io.github.yourimartin.gatewai.domain.port.out.AnswerJudge;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.EmbeddingWindow;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Would the cached answer have been right? (v4 D.2) For every shadow would-hit,
 * compares the answer the cache would have served with the one the client
 * actually received:
 *
 * <ul>
 *   <li>always, by the cosine similarity of the two answers under the in-process
 *       embedding model — which sees only its window of each (128 tokens for the
 *       default model), so a longer answer is flagged {@code beyondWindow};</li>
 *   <li>on a sample ({@code gatewai.shadow.judge.sample-rate}, 0 by default), by an
 *       LLM judge: a registry model, called directly, grading under
 *       {@link JudgeRubric}. Each judge call is accounted like any request, under
 *       the system client {@link #JUDGE_CLIENT_ID}.</li>
 * </ul>
 *
 * <p>Both answers are compared in memory and discarded: the assessment holds
 * numbers and labels only. Runs on the shadow evaluation's thread, inside its
 * concurrency limit, and never throws.
 */
@Service
final class WouldHitAssessor {

  /** The {@code request_log.client_id} of every judge call. */
  static final String JUDGE_CLIENT_ID = "gatewai:shadow-judge";

  private static final Logger LOG = LoggerFactory.getLogger(WouldHitAssessor.class);

  private final TextEmbedder embedder;
  private final EmbeddingWindow window;
  private final AnswerJudge judge;
  private final DecisionRecorder recorder;
  private final MetricsRecorder metrics;
  private final RequestAccounting accounting;
  private final double sampleRate;
  private final String judgeModel;
  private final DoubleSupplier random;

  @Autowired
  WouldHitAssessor(TextEmbedder embedder, EmbeddingWindow window, AnswerJudge judge,
                   DecisionRecorder recorder, MetricsRecorder metrics,
                   ModelRegistry modelRegistry, RequestLogRepository requestLogRepository,
                   CarbonIntensityProvider carbonIntensityProvider,
                   GreenAccountant greenAccountant, ProviderRegions providerRegions,
                   CloudRegionZones cloudRegionZones,
                   @Value("${gatewai.shadow.judge.sample-rate:0}") double sampleRate,
                   @Value("${gatewai.shadow.judge.model:}") String judgeModel) {
    this(embedder, window, judge, recorder, metrics, modelRegistry,
        new RequestAccounting(requestLogRepository, modelRegistry, carbonIntensityProvider,
            greenAccountant, metrics, providerRegions, cloudRegionZones),
        sampleRate, judgeModel, () -> ThreadLocalRandom.current().nextDouble());
  }

  /** Test seam: a fixed accounting and a scripted sampler. */
  WouldHitAssessor(TextEmbedder embedder, EmbeddingWindow window, AnswerJudge judge,
                   DecisionRecorder recorder, MetricsRecorder metrics,
                   ModelRegistry modelRegistry, RequestAccounting accounting,
                   double sampleRate, String judgeModel, DoubleSupplier random) {
    this.judgeModel = validJudge(sampleRate, judgeModel, modelRegistry);
    this.embedder = embedder;
    this.window = window;
    this.judge = judge;
    this.recorder = recorder;
    this.metrics = metrics;
    this.accounting = accounting;
    this.sampleRate = sampleRate;
    this.random = random;
  }

  /**
   * A sample rate outside [0, 1], or a judge the registry does not hold, fails
   * startup: either would otherwise surface as a silent absence of verdicts.
   */
  private static String validJudge(double rate, String model, ModelRegistry registry) {
    if (!(rate >= 0 && rate <= 1)) {
      throw new IllegalStateException(
          "gatewai.shadow.judge.sample-rate must be between 0 and 1, was " + rate);
    }
    if (rate == 0) {
      return null;
    }
    String id = model == null ? "" : model.strip();
    if (id.isEmpty() || registry.findByModelId(id).isEmpty()) {
      throw new IllegalStateException("gatewai.shadow.judge.sample-rate=" + rate
          + " needs gatewai.shadow.judge.model to be a model id of gatewai.models.registry,"
          + " was '" + id + "'");
    }
    return id;
  }

  /**
   * Assesses one would-hit and records it. Never throws.
   *
   * @param context  the shadow request's context
   * @param request  the shadow request
   * @param cached   the answer the cache would have served
   * @param received the answer the client received
   */
  void assess(RequestContext context, LlmRequest request, String cached,
              RelayedAnswer received) {
    try {
      Double similarity = similarity(cached, received.text());
      boolean beyondWindow = !fits(cached) || !fits(received.text());
      Judged judged = sampled() ? judge(request, cached, received.text()) : Judged.NONE;
      WouldHitAssessment assessment = new WouldHitAssessment(UUID.randomUUID(),
          context.traceId(), context.clientId(), Instant.now(), similarity, beyondWindow,
          similarity == null ? null : embedder.modelId(), judged.outcome(), judged.verdict(),
          judged.outcome() == JudgeOutcome.NOT_SAMPLED ? null : judgeModel,
          judged.outcome() == JudgeOutcome.NOT_SAMPLED ? null : JudgeRubric.VERSION,
          judged.correlationId());
      recorder.record(assessment);
      metrics.recordShadowJudgement(assessment.judgeOutcome(), assessment.verdict());
    } catch (RuntimeException e) {
      LOG.warn("Could not assess the would-hit of request {}: {}", context.traceId(),
          e.toString());
    }
  }

  private boolean sampled() {
    return judgeModel != null && random.getAsDouble() < sampleRate;
  }

  private Double similarity(String a, String b) {
    try {
      return Similarity.cosine(embedder.embed(a), embedder.embed(b));
    } catch (RuntimeException e) {
      LOG.warn("Could not embed a would-hit's answers: {}", e.toString());
      return null;
    }
  }

  private boolean fits(String text) {
    try {
      return window.fits(text);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** One judge call, accounted under the judge's own client and correlation id. */
  private Judged judge(LlmRequest request, String cached, String received) {
    List<LlmMessage> prompt = JudgeRubric.prompt(request.messages(), received, cached);
    String correlationId = UUID.randomUUID().toString();
    long start = System.nanoTime();
    AnswerJudge.Reply reply;
    try {
      reply = judge.judge(judgeModel, prompt);
    } catch (RuntimeException e) {
      LOG.warn("Judge '{}' failed on a would-hit: {}", judgeModel, e.toString());
      return new Judged(JudgeOutcome.FAILED, null, null);
    }
    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    TokenUsage usage = reply.usage() == null ? TokenUsage.NONE : reply.usage();
    try {
      accounting.record(new LlmRequest(judgeModel, prompt, 0.0, null), judgeModel, usage,
          false, latencyMs, correlationId, JUDGE_CLIENT_ID);
    } catch (RuntimeException e) {
      LOG.warn("Could not account judge call {}: {}", correlationId, e.toString());
    }
    return new Judged(JudgeOutcome.JUDGED, JudgeVerdict.parse(reply.text()), correlationId);
  }

  private record Judged(JudgeOutcome outcome, JudgeVerdict verdict, String correlationId) {

    static final Judged NONE = new Judged(JudgeOutcome.NOT_SAMPLED, null, null);
  }
}
