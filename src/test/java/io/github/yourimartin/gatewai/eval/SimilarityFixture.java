package io.github.yourimartin.gatewai.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Recorded cosine similarity of each labelled {@code (query, entry)} pair
 * (v2 batch 5).
 *
 * <p>Similarities rather than vectors, because there is nothing else to replay:
 * the cache's decision is {@code bestScore >= threshold}, and the score itself
 * comes from the vector store, not from gateway logic. What this fixture pins
 * down is the model's opinion of each pair; what the harness varies is the
 * threshold policy applied to it — which is precisely what batch 3 calibrates.
 */
final class SimilarityFixture {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final FixtureProvenance provenance;
  private final Map<String, Double> similarities;

  SimilarityFixture(FixtureProvenance provenance, Map<String, Double> similarities) {
    this.provenance = provenance;
    this.similarities = new LinkedHashMap<>(similarities);
  }

  FixtureProvenance provenance() {
    return provenance;
  }

  /** Similarity by {@link CachePair#id()}. */
  Map<String, Double> similarities() {
    return Map.copyOf(similarities);
  }

  static SimilarityFixture load(String resource) {
    try (InputStream in = SimilarityFixture.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(
            "Missing fixture " + resource + " — record it with: "
                + EvalPaths.RECORD_COMMAND);
      }
      JsonNode root = MAPPER.readTree(in);
      Map<String, Double> similarities = new LinkedHashMap<>();
      JsonNode recorded = root.path("similarities");
      recorded.properties().forEach(entry ->
          similarities.put(entry.getKey(), entry.getValue().asDouble()));

      return new SimilarityFixture(
          new FixtureProvenance(
              root.path("embeddingModel").asString(),
              root.path("dimensions").asInt(),
              root.path("recordedAt").asString(),
              root.path("datasetDigest").asString(),
              root.path("routingConfigVersion").asString()),
          similarities);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + resource, e);
    }
  }

  void write(Path target) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("embeddingModel", provenance.embeddingModel());
    root.put("dimensions", provenance.dimensions());
    root.put("recordedAt", provenance.recordedAt());
    root.put("datasetDigest", provenance.datasetDigest());
    root.put("routingConfigVersion", provenance.routingConfigVersion());

    ObjectNode recorded = root.putObject("similarities");
    similarities.forEach(recorded::put);

    try {
      Files.createDirectories(target.getParent());
      Files.writeString(target,
          MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write " + target, e);
    }
  }
}
