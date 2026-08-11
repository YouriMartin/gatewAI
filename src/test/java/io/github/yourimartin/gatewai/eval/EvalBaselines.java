package io.github.yourimartin.gatewai.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The floor each metric must stay above for the build to pass (v2 batch 5).
 *
 * <p>Committed numbers, set from a measured run and deliberately a little below
 * it. They are a <b>regression detector</b>, not a target: the harness replays
 * fixed fixtures, so a drop between two commits means the decision code changed
 * its mind about prompts it used to get right. Raising a baseline after a real
 * improvement is the intended way to ratchet.
 *
 * <p>A model change moves these numbers legitimately — that is a re-record plus
 * a deliberate baseline edit, visible in the diff, which is the point.
 */
final class EvalBaselines {

  private static final String RESOURCE = "/eval/baselines.json";

  private final JsonNode root;

  private EvalBaselines(JsonNode root) {
    this.root = root;
  }

  static EvalBaselines load() {
    try (InputStream in = EvalBaselines.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing " + RESOURCE);
      }
      return new EvalBaselines(new ObjectMapper().readTree(in));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + RESOURCE, e);
    }
  }

  double get(String key) {
    JsonNode value = root.get(key);
    if (value == null || !value.isNumber()) {
      throw new IllegalStateException("Missing baseline: " + key);
    }
    return value.asDouble();
  }
}
