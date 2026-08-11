package io.github.yourimartin.gatewai.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fixture format has to be lossless, or the harness would silently score
 * something the model never said.
 */
class VectorFixtureTest {

  private static final FixtureProvenance PROVENANCE =
      new FixtureProvenance("nomic-embed-text", 3, "2026-08-11T00:00:00Z", "digest", "config");

  @Test
  void roundTripsVectorsBitForBit(@TempDir Path directory) {
    float[] awkward = {0.1f, -0.0f, Float.MIN_VALUE, 1.0f / 3, -12345.6789f};
    Path file = directory.resolve("vectors.json");

    new VectorFixture(PROVENANCE, new LatencyStats(1.5, 2.5, 7),
        Map.of("hello", awkward)).write(file);

    VectorFixture reloaded = VectorFixture.read(file);
    assertArrayEquals(awkward, reloaded.vectors().get("hello"),
        "a lossy fixture would move decisions near the threshold");
    assertEquals(2.5, reloaded.decisionLatency().p95());
    assertEquals("digest", reloaded.provenance().datasetDigest());
  }

  @Test
  void replayRefusesAnUnknownText() {
    ReplayEmbeddingModel model =
        new ReplayEmbeddingModel(Map.of("known", new float[] {1, 0, 0}), 3);

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> model.embed("unknown"));
    assertTrue(failure.getMessage().contains("stale"),
        "a missing vector must point at re-recording, never fabricate one");
  }
}
