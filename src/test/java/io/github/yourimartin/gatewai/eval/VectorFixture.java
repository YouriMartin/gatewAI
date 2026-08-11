package io.github.yourimartin.gatewai.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Recorded embedding vectors, so the harness can score the real classifier
 * without a model server (v2 batch 5).
 *
 * <p>The alternative — recording only the similarity of each prompt to each
 * route — would have forced the harness to re-implement route ranking, the
 * threshold and the hand-over to the heuristic. Recording vectors instead keeps
 * {@code EmbeddingComplexityClassifier} itself under test: the fixture replaces
 * Ollama, nothing else.
 *
 * <p>Vectors are stored as base64 little-endian {@code float32}, which is the
 * exact bit pattern the model returned — no quantisation, so a hermetic run and
 * a live run cannot disagree about a decision near the threshold. One text per
 * line keeps the file greppable; it is a generated artefact and is meant to be
 * regenerated wholesale, never edited.
 */
final class VectorFixture {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int BYTES_PER_FLOAT = 4;

  private final FixtureProvenance provenance;
  private final LatencyStats decisionLatency;
  private final Map<String, float[]> vectors;

  VectorFixture(FixtureProvenance provenance, LatencyStats decisionLatency,
                Map<String, float[]> vectors) {
    this.provenance = provenance;
    this.decisionLatency = decisionLatency;
    this.vectors = new LinkedHashMap<>(vectors);
  }

  FixtureProvenance provenance() {
    return provenance;
  }

  /**
   * Decision latency measured live at recording time, in milliseconds — the
   * classifier call only, never the model call it precedes. It cannot be
   * measured hermetically (replay is a hash-map lookup), so it is carried here
   * and reported as what it is: recorded, on the recording machine.
   */
  LatencyStats decisionLatency() {
    return decisionLatency;
  }

  Map<String, float[]> vectors() {
    return Map.copyOf(vectors);
  }

  static VectorFixture load(String resource) {
    try (InputStream in = VectorFixture.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(
            "Missing fixture " + resource + " — record it with: "
                + EvalPaths.RECORD_COMMAND);
      }
      return of(MAPPER.readTree(in));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + resource, e);
    }
  }

  /** Reads a fixture straight off disk, as the recorder just wrote it. */
  static VectorFixture read(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return of(MAPPER.readTree(in));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + file, e);
    }
  }

  private static VectorFixture of(JsonNode root) {
    Map<String, float[]> vectors = new LinkedHashMap<>();
    JsonNode recorded = root.path("vectors");
    recorded.propertyNames().forEach(text ->
        vectors.put(text, decode(recorded.path(text).asString())));

    JsonNode latency = root.path("decisionLatencyMillis");
    return new VectorFixture(
        new FixtureProvenance(
            root.path("embeddingModel").asString(),
            root.path("dimensions").asInt(),
            root.path("recordedAt").asString(),
            root.path("datasetDigest").asString(),
            root.path("routingConfigVersion").asString()),
        new LatencyStats(latency.path("p50").asDouble(), latency.path("p95").asDouble(),
            latency.path("samples").asInt()),
        vectors);
  }

  void write(Path target) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("embeddingModel", provenance.embeddingModel());
    root.put("dimensions", provenance.dimensions());
    root.put("recordedAt", provenance.recordedAt());
    root.put("datasetDigest", provenance.datasetDigest());
    root.put("routingConfigVersion", provenance.routingConfigVersion());

    ObjectNode latency = root.putObject("decisionLatencyMillis");
    latency.put("p50", decisionLatency.p50());
    latency.put("p95", decisionLatency.p95());
    latency.put("samples", decisionLatency.samples());

    ObjectNode encoded = root.putObject("vectors");
    vectors.forEach((text, vector) -> encoded.put(text, encode(vector)));

    try {
      Files.createDirectories(target.getParent());
      Files.writeString(target,
          MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write " + target, e);
    }
  }

  private static String encode(float[] vector) {
    ByteBuffer buffer = ByteBuffer.allocate(vector.length * BYTES_PER_FLOAT)
        .order(ByteOrder.LITTLE_ENDIAN);
    for (float value : vector) {
      buffer.putFloat(value);
    }
    return Base64.getEncoder().encodeToString(buffer.array());
  }

  private static float[] decode(String encoded) {
    ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded))
        .order(ByteOrder.LITTLE_ENDIAN);
    float[] vector = new float[buffer.remaining() / BYTES_PER_FLOAT];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = buffer.getFloat();
    }
    return vector;
  }
}
