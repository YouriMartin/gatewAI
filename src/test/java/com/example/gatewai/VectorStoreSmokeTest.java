package com.example.gatewai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("integration")
@SpringBootTest
class VectorStoreSmokeTest {

  @Autowired
  private VectorStore vectorStore;

  @Test
  void storeAndRetrieveBySemanticSimilarity() {
    var doc1 = new Document(
        "The cat sat on the mat and purred softly.");
    var doc2 = new Document(
        "Java virtual threads improve scalability of server applications.");
    var doc3 = new Document(
        "Spring AI simplifies integration with large language models.");

    vectorStore.add(List.of(doc1, doc2, doc3));

    try {
      List<Document> results = vectorStore.similaritySearch(
          SearchRequest.builder()
              .query("How do virtual threads help performance?")
              .topK(1)
              .build());

      assertFalse(results.isEmpty(), "Expected at least one result");
      assertEquals(doc2.getId(), results.get(0).getId());
    } finally {
      vectorStore.delete(List.of(doc1.getId(), doc2.getId(), doc3.getId()));
    }
  }

  @Test
  void similarityThresholdFiltersLowScoreResults() {
    var doc = new Document("Raindrops keep falling on my head.");

    vectorStore.add(List.of(doc));

    try {
      List<Document> results = vectorStore.similaritySearch(
          SearchRequest.builder()
              .query("Quantum computing breakthroughs in 2024")
              .topK(10)
              .similarityThreshold(0.95)
              .build());

      assertTrue(results.isEmpty(),
          "Expected no results above 0.95 similarity threshold");
    } finally {
      vectorStore.delete(List.of(doc.getId()));
    }
  }
}
