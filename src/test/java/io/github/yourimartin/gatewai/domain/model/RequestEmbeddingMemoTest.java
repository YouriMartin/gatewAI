package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RequestEmbeddingMemoTest {

  @Test
  void memoizesRepeatedTextWithinOneScope() {
    AtomicInteger calls = new AtomicInteger();

    RequestEmbeddingMemo.runWith(() -> {
      RequestEmbeddingMemo memo = RequestEmbeddingMemo.current().orElseThrow();

      float[] first = memo.computeIfAbsent("hello", "nomic",
          text -> vector(calls.incrementAndGet()));
      float[] second = memo.computeIfAbsent("hello", "nomic",
          text -> vector(calls.incrementAndGet()));

      assertEquals(1, calls.get());
      assertSame(first, second);
      assertEquals(1, memo.size());
    });
  }

  @Test
  void differentTextsAreComputedSeparately() {
    AtomicInteger calls = new AtomicInteger();

    RequestEmbeddingMemo.runWith(() -> {
      RequestEmbeddingMemo memo = RequestEmbeddingMemo.current().orElseThrow();
      memo.computeIfAbsent("a", "nomic", t -> vector(calls.incrementAndGet()));
      memo.computeIfAbsent("b", "nomic", t -> vector(calls.incrementAndGet()));

      assertEquals(2, calls.get());
      assertEquals(2, memo.size());
    });
  }

  @Test
  void scopesAreIndependent() {
    AtomicInteger calls = new AtomicInteger();

    RequestEmbeddingMemo.runWith(() -> RequestEmbeddingMemo.current().orElseThrow()
        .computeIfAbsent("hello", "nomic", t -> vector(calls.incrementAndGet())));
    RequestEmbeddingMemo.runWith(() -> RequestEmbeddingMemo.current().orElseThrow()
        .computeIfAbsent("hello", "nomic", t -> vector(calls.incrementAndGet())));

    // One memo per request: the second request must not read the first's vector.
    assertEquals(2, calls.get());
  }

  @Test
  void recordsTheModelIdThatProducedTheVectors() {
    RequestEmbeddingMemo.runWith(() -> {
      RequestEmbeddingMemo memo = RequestEmbeddingMemo.current().orElseThrow();
      assertTrue(memo.embeddingModelId().isEmpty());

      memo.computeIfAbsent("hello", "nomic-embed-text", t -> vector(1));

      assertEquals("nomic-embed-text", memo.embeddingModelId().orElseThrow());
    });
  }

  @Test
  void staysBoundedAndStillReturnsCorrectVectors() {
    RequestEmbeddingMemo.runWith(() -> {
      RequestEmbeddingMemo memo = RequestEmbeddingMemo.current().orElseThrow();
      for (int i = 0; i < RequestEmbeddingMemo.MAX_ENTRIES + 5; i++) {
        int value = i;
        float[] result =
            memo.computeIfAbsent("text-" + i, "nomic", t -> vector(value));
        assertArrayEquals(vector(value), result);
      }
      assertEquals(RequestEmbeddingMemo.MAX_ENTRIES, memo.size());
    });
  }

  @Test
  void returnsEmptyOutsideAnyScope() {
    assertFalse(RequestEmbeddingMemo.current().isPresent());
  }

  @Test
  void callWithReturnsTheBodyResult() {
    String result = RequestEmbeddingMemo.callWith(
        () -> RequestEmbeddingMemo.current().isPresent() ? "bound" : "unbound");

    assertEquals("bound", result);
  }

  private static float[] vector(int seed) {
    return new float[] {seed, seed + 1f, seed + 2f};
  }
}
