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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * Records the evaluation fixtures against a live embedding model (v2 batch 5).
 *
 * <p>It is not part of any automated run: {@code ./mvnw test} must stay
 * hermetic, and CI must never rewrite committed fixtures. Run it by hand after
 * editing a dataset, changing the routes or swapping the embedding model:
 *
 * <pre>{@code
 * ./mvnw test -Dtest=EvalFixtureRecorderTest -Deval.record=true
 * }</pre>
 *
 * <p>Since v3 lot A it needs <b>no infrastructure at all</b> — the embedding
 * model runs in-process from the jar's own resources, so it dropped the
 * {@code integration} tag. What still keeps an automated run from rewriting
 * fixtures is the {@code -Deval.record=true} gate, which is the guard that
 * mattered.
 *
 * <p>It records exactly the texts the classifier asks for, by wrapping the real
 * model in a capturing decorator rather than guessing which texts matter. Add a
 * route, and its examples are recorded because the classifier embedded them.
 *
 * <p>No Spring context, no database, no migrations, no model server: it builds
 * the same ONNX model the application ships, straight from
 * {@code application.properties}.
 */
@EnabledIfSystemProperty(named = "eval.record", matches = "true")
class EvalFixtureRecorderTest {

  @Test
  void recordsRoutingVectorsAndCacheSimilarities() {
    EvalConfig config = EvalConfig.load();
    RoutingConfig routingConfig = config.routingConfig();
    String modelId = config.embeddingModelId();

    CapturingEmbeddingModel model = new CapturingEmbeddingModel(inProcess(config));
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

  /**
   * The shipped embedding model, built exactly as the application builds it —
   * from the resource paths in {@code application.properties}. Reading them
   * rather than restating them is what keeps a re-recording honest after the
   * model is swapped (v3 batch A.3).
   */
  private static EmbeddingModel inProcess(EvalConfig config) {
    TransformersEmbeddingModel model = new TransformersEmbeddingModel();
    model.setModelResource(config.embeddingModelResource());
    model.setTokenizerResource(config.embeddingTokenizerResource());
    model.setTokenizerOptions(Map.of("padding", "true"));
    try {
      model.afterPropertiesSet();
    } catch (Exception e) {
      throw new IllegalStateException(
          "could not load the bundled embedding model — run `git lfs pull`", e);
    }
    return model;
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
