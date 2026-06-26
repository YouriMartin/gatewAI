package com.example.gatewai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("integration")
@SpringBootTest
class EmbeddingModelSmokeTest {

  private static final int EXPECTED_DIMENSIONS = 768;

  @Autowired
  private EmbeddingModel embeddingModel;

  @Test
  void embedsTextToExpectedDimensions() {
    float[] embedding = embeddingModel.embed("Hello, world!");

    assertEquals(EXPECTED_DIMENSIONS, embedding.length,
        "nomic-embed-text should produce 768-dimensional vectors");
  }

  @Test
  void reportsDimensionsConsistentWithConfig() {
    assertEquals(EXPECTED_DIMENSIONS, embeddingModel.dimensions(),
        "EmbeddingModel.dimensions() should match configured value");
  }

  @Test
  void differentTextsProduceDifferentEmbeddings() {
    float[] embedding1 = embeddingModel.embed(
        "Java virtual threads improve server scalability.");
    float[] embedding2 = embeddingModel.embed(
        "The best recipe for chocolate cake involves cocoa powder.");

    assertFalse(Arrays.equals(embedding1, embedding2),
        "Unrelated texts should produce different embeddings");
  }
}
