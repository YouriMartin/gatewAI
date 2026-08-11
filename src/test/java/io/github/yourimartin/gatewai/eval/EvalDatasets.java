package io.github.yourimartin.gatewai.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ModelTier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the labelled evaluation sets (v2 batch 5).
 *
 * <p>JSON Lines, one labelled case per line, versioned in the repository next to
 * the code they judge. One line per case keeps a relabelling to a one-line diff,
 * which matters because these labels are hand-made and will be argued with.
 *
 * <p>Calibration and test sets are <b>disjoint</b> by construction and asserted
 * to be so by {@code EvaluationHarnessTest}: batch 3 fits its quantile on the
 * calibration set, and a coverage figure measured on the same prompts would be
 * meaningless.
 */
final class EvalDatasets {

  static final String ROUTING_CALIBRATION = "/eval/routing-calibration.jsonl";
  static final String ROUTING_TEST = "/eval/routing-test.jsonl";
  static final String CACHE_CALIBRATION = "/eval/cache-calibration.jsonl";
  static final String CACHE_TEST = "/eval/cache-test.jsonl";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int DIGEST_LENGTH = 16;

  private EvalDatasets() {
  }

  static List<RoutingSample> routing(String resource) {
    List<RoutingSample> samples = new ArrayList<>();
    for (JsonNode node : read(resource)) {
      samples.add(new RoutingSample(
          node.path("id").asString(),
          node.path("prompt").asString(),
          ModelTier.valueOf(node.path("expectedTier").asString()),
          node.path("language").asString(),
          strings(node.path("tags"))));
    }
    return List.copyOf(samples);
  }

  static List<CachePair> cache(String resource) {
    List<CachePair> pairs = new ArrayList<>();
    for (JsonNode node : read(resource)) {
      pairs.add(new CachePair(
          node.path("id").asString(),
          node.path("query").asString(),
          node.path("entry").asString(),
          "YES".equals(node.path("judgment").asString()),
          node.path("language").asString(),
          strings(node.path("tags"))));
    }
    return List.copyOf(pairs);
  }

  /**
   * Fingerprint of the raw dataset files, stamped into the recorded fixtures.
   *
   * <p>Edit a prompt and this changes, which is how the harness knows the
   * fixtures no longer describe the data it is about to score — the same
   * provenance discipline batch 2 applies to routing decisions.
   */
  static String digest(String... resources) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is guaranteed by the JDK", e);
    }
    for (String resource : resources) {
      digest.update(resource.getBytes(StandardCharsets.UTF_8));
      digest.update(raw(resource).getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(digest.digest()).substring(0, DIGEST_LENGTH);
  }

  private static List<JsonNode> read(String resource) {
    List<JsonNode> nodes = new ArrayList<>();
    for (String line : raw(resource).split("\n")) {
      if (!line.isBlank()) {
        nodes.add(MAPPER.readTree(line));
      }
    }
    return nodes;
  }

  private static String raw(String resource) {
    try (InputStream in = EvalDatasets.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing evaluation dataset: " + resource);
      }
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(in, StandardCharsets.UTF_8))) {
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          text.append(line).append('\n');
        }
        return text.toString();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + resource, e);
    }
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(node -> values.add(node.asString()));
    return values;
  }
}
