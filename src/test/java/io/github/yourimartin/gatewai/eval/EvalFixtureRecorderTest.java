package io.github.yourimartin.gatewai.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.RoutingConfigVersion;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;
import io.github.yourimartin.gatewai.infrastructure.llm.EvalClassifierFactory;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;

/**
 * Records the evaluation fixtures against a live embedding model (v2 batch 5).
 *
 * <p>This is the only part of the harness that needs infrastructure, and it is
 * not part of any automated run: {@code ./mvnw test} must stay hermetic, and CI
 * must never rewrite committed fixtures. Run it by hand after editing a dataset
 * or changing the routes:
 *
 * <pre>{@code
 * docker compose up -d ollama
 * ./mvnw -Pit test -Dtest=EvalFixtureRecorderTest -Deval.record=true
 * }</pre>
 *
 * <p>It records exactly the texts the classifier asks for, by wrapping the real
 * model in a capturing decorator rather than guessing which texts matter. Add a
 * route, and its examples are recorded because the classifier embedded them.
 *
 * <p>No Spring context: the recorder needs Ollama and nothing else — no
 * database, no migrations, no chat models to pull.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "eval.record", matches = "true")
class EvalFixtureRecorderTest {

  @Test
  void recordsRoutingVectorsAndCacheSimilarities() {
    EvalConfig config = EvalConfig.load();
    RoutingConfig routingConfig = config.routingConfig();
    String modelId = config.embeddingModelId();

    CapturingEmbeddingModel model = new CapturingEmbeddingModel(ollama(modelId));
    // Recorded at the fixed threshold: fixtures hold vectors, and the vectors do
    // not depend on which threshold reads them.
    ComplexityClassifier classifier = EvalClassifierFactory.embeddingClassifier(
        model, routingConfig,
        CalibrationFixtures.none(routingConfig.routeSimilarityThreshold()));

    List<RoutingSample> routingSamples = new ArrayList<>();
    routingSamples.addAll(EvalDatasets.routing(EvalDatasets.ROUTING_CALIBRATION));
    routingSamples.addAll(EvalDatasets.routing(EvalDatasets.ROUTING_TEST));

    List<Double> latencies = new ArrayList<>();
    for (RoutingSample sample : routingSamples) {
      long start = System.nanoTime();
      classifier.classify(sample.prompt());
      latencies.add((System.nanoTime() - start) / (double) TimeUnit.MILLISECONDS.toNanos(1));
    }

    String routingDigest = EvalDatasets.digest(
        EvalDatasets.ROUTING_CALIBRATION, EvalDatasets.ROUTING_TEST);
    new VectorFixture(
        new FixtureProvenance(modelId, model.dimensions(), Instant.now().toString(),
            routingDigest, RoutingConfigVersion.of(routingConfig)),
        LatencyStats.of(latencies),
        model.captured())
        .write(EvalPaths.FIXTURE_SOURCE_DIR.resolve("routing-vectors.json"));

    List<CachePair> pairs = new ArrayList<>();
    pairs.addAll(EvalDatasets.cache(EvalDatasets.CACHE_CALIBRATION));
    pairs.addAll(EvalDatasets.cache(EvalDatasets.CACHE_TEST));

    Map<String, Double> similarities = new LinkedHashMap<>();
    for (CachePair pair : pairs) {
      similarities.put(pair.id(),
          cosine(model.embed(pair.query()), model.embed(pair.entry())));
    }

    String cacheDigest = EvalDatasets.digest(
        EvalDatasets.CACHE_CALIBRATION, EvalDatasets.CACHE_TEST);
    new SimilarityFixture(
        new FixtureProvenance(modelId, model.dimensions(), Instant.now().toString(),
            cacheDigest, RoutingConfigVersion.of(routingConfig)),
        similarities)
        .write(EvalPaths.FIXTURE_SOURCE_DIR.resolve("cache-similarities.json"));

    System.out.printf("Recorded %d vectors and %d pair similarities into %s%n",
        model.captured().size(), similarities.size(), EvalPaths.FIXTURE_SOURCE_DIR);
  }

  private static EmbeddingModel ollama(String modelId) {
    String baseUrl = System.getenv().getOrDefault("SPRING_AI_OLLAMA_BASE_URL",
        System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434"));
    return OllamaEmbeddingModel.builder()
        .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
        .options(OllamaEmbeddingOptions.builder().model(modelId).build())
        .build();
  }

  /**
   * Cosine similarity, the same quantity pgvector reports as
   * {@code 1 - (a <=> b)} and the same one
   * {@code EmbeddingComplexityClassifier} computes for routes.
   */
  private static double cosine(float[] a, float[] b) {
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < Math.min(a.length, b.length); i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  /** Delegates to the real model and keeps every {@code text → vector} it sees. */
  private static final class CapturingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final Map<String, float[]> captured = new LinkedHashMap<>();

    private CapturingEmbeddingModel(EmbeddingModel delegate) {
      this.delegate = delegate;
    }

    Map<String, float[]> captured() {
      return captured;
    }

    @Override
    public float[] embed(String text) {
      return captured.computeIfAbsent(text, delegate::embed).clone();
    }

    @Override
    public float[] embed(Document document) {
      return embed(document.getText());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      List<Embedding> embeddings = new ArrayList<>();
      List<String> instructions = request.getInstructions();
      for (int i = 0; i < instructions.size(); i++) {
        embeddings.add(new Embedding(embed(instructions.get(i)), i));
      }
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public int dimensions() {
      return delegate.dimensions();
    }
  }
}
