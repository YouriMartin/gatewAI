package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;

@ExtendWith(MockitoExtension.class)
class MemoizingEmbeddingModelTest {

  private static final float[] VECTOR = {0.1f, 0.2f, 0.3f};

  @Mock
  private EmbeddingModel delegate;
  @Mock
  private BatchingStrategy batchingStrategy;

  @Test
  void embedsOnceWhenTheSameTextIsRequestedTwiceInAScope() {
    // The real pair: the vector store embeds the query, then the classifier
    // embeds the same user text. Both land on embed(String).
    when(delegate.embed("summarize this")).thenReturn(VECTOR);
    MemoizingEmbeddingModel model =
        new MemoizingEmbeddingModel(delegate, "nomic-embed-text");

    RequestEmbeddingMemo.runWith(() -> {
      assertArrayEquals(VECTOR, model.embed("summarize this"));
      assertArrayEquals(VECTOR, model.embed("summarize this"));
    });

    verify(delegate, times(1)).embed("summarize this");
  }

  @Test
  void embedsEveryTimeWithoutABoundMemo() {
    // Degradation must be "slower", never "wrong": no scope, no memo, and the
    // model behaves exactly as the undecorated one.
    when(delegate.embed("hello")).thenReturn(VECTOR);
    MemoizingEmbeddingModel model =
        new MemoizingEmbeddingModel(delegate, "nomic-embed-text");

    assertArrayEquals(VECTOR, model.embed("hello"));
    assertArrayEquals(VECTOR, model.embed("hello"));

    verify(delegate, times(2)).embed("hello");
  }

  @Test
  void storingTheCacheMissReusesTheQueryVector() {
    // Cache miss: search with the text, then store it as a single document.
    // The store must not re-embed what the search already computed.
    when(delegate.embed("summarize this")).thenReturn(VECTOR);
    when(delegate.getEmbeddingContent(any(Document.class)))
        .thenAnswer(invocation ->
            invocation.getArgument(0, Document.class).getText());
    MemoizingEmbeddingModel model =
        new MemoizingEmbeddingModel(delegate, "nomic-embed-text");

    RequestEmbeddingMemo.runWith(() -> {
      model.embed("summarize this");
      List<float[]> stored = model.embed(
          List.of(new Document("summarize this")), null, batchingStrategy);
      assertArrayEquals(VECTOR, stored.getFirst());
    });

    verify(delegate, never()).embed(anyList(), any(), any());
  }

  @Test
  void storingAnUnrelatedDocumentStillDelegates() {
    when(delegate.getEmbeddingContent(any(Document.class)))
        .thenAnswer(invocation ->
            invocation.getArgument(0, Document.class).getText());
    when(delegate.embed(anyList(), any(), any())).thenReturn(List.of(VECTOR));
    MemoizingEmbeddingModel model =
        new MemoizingEmbeddingModel(delegate, "nomic-embed-text");

    RequestEmbeddingMemo.runWith(() -> model.embed(
        List.of(new Document("never seen")), null, batchingStrategy));

    verify(delegate).embed(anyList(), any(), any());
  }

  @Test
  void batchEmbeddingIsNotMemoized() {
    // Route-example indexing has its own cache; it must not evict the hot path.
    when(delegate.embed(List.of("a", "b"))).thenReturn(List.of(VECTOR, VECTOR));
    MemoizingEmbeddingModel model =
        new MemoizingEmbeddingModel(delegate, "nomic-embed-text");

    RequestEmbeddingMemo.runWith(() -> {
      model.embed(List.of("a", "b"));
      model.embed(List.of("a", "b"));
      assertEquals(0,
          RequestEmbeddingMemo.current().orElseThrow().size());
    });

    verify(delegate, times(2)).embed(List.of("a", "b"));
  }

  @Test
  void dimensionsAreDelegated() {
    when(delegate.dimensions()).thenReturn(768);
    MemoizingEmbeddingModel model =
        new MemoizingEmbeddingModel(delegate, "nomic-embed-text");

    assertEquals(768, model.dimensions());
  }

  @Test
  void exposesTheModelIdForProvenance() {
    assertEquals("nomic-embed-text",
        new MemoizingEmbeddingModel(delegate, "nomic-embed-text").modelId());
  }
}
