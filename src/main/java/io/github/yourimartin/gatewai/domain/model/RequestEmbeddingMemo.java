package io.github.yourimartin.gatewai.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Per-request memo of computed embedding vectors (v2 batch 0.2).
 *
 * <p>An uncached request used to embed the same user text twice: once inside
 * the semantic cache (the {@code VectorStore} embeds the query itself) and once
 * in the router's embedding classifier. Spring AI's {@code SearchRequest}
 * only accepts a query <em>string</em>, so a precomputed vector cannot be handed
 * to the cache without bypassing the {@code VectorStore} interface — which
 * {@code ADR 0005} forbids. The memo solves it one level lower instead: it is
 * consulted by the memoizing {@code EmbeddingModel} decorator, the {@code EmbeddingModel} bean
 * that both the vector store and the classifier are injected with, so the second
 * lookup is a map hit.
 *
 * <p>Bound as a {@link ScopedValue} by {@code SpringAiLlmClient} around the
 * whole advisor chain. When it is not bound — a classifier call outside a chat
 * request, a test, an embedding computed after the scope closed — nothing
 * breaks: the model simply embeds as before. <b>An absent memo is never
 * incorrect, only slower.</b>
 *
 * <p>Deliberately tiny and bounded: occlusion attribution (batch 7) embeds many
 * variants of one prompt, and this must not turn into an unbounded per-request
 * heap of 768-float vectors.
 *
 * <p>It lives in the domain for the same reason {@link RequestContext} and
 * {@link CarbonZoneContext} do: several adapters read it — the router and the
 * cache tracer — and adapters may not depend on one another. Like them, it uses
 * JDK types only.
 */
public final class RequestEmbeddingMemo {

  /** Vectors kept per request. The hot path needs 1; the cap is slack. */
  public static final int MAX_ENTRIES = 8;

  /** Carrier for the current request's memo, bound around the advisor chain. */
  public static final ScopedValue<RequestEmbeddingMemo> CURRENT =
      ScopedValue.newInstance();

  private final Map<String, float[]> vectors = new LinkedHashMap<>();

  /** Set on first store: which model produced the vectors in this memo. */
  private String embeddingModelId;

  private RequestEmbeddingMemo() {
  }

  /**
   * Runs {@code body} with a fresh memo bound, and returns its result.
   *
   * @param body the work to run under the memo (typically the advisor chain)
   * @param <R>  the body's result type
   */
  public static <R> R callWith(Supplier<R> body) {
    return ScopedValue.where(CURRENT, new RequestEmbeddingMemo())
        .call(body::get);
  }

  /** Runs {@code body} with a fresh memo bound. */
  public static void runWith(Runnable body) {
    ScopedValue.where(CURRENT, new RequestEmbeddingMemo()).run(body);
  }

  /** The memo bound to the current scope, empty when none is. */
  public static Optional<RequestEmbeddingMemo> current() {
    return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
  }

  /**
   * Identifier of the embedding model whose vectors this memo holds, empty
   * until something is stored. Batch 2 persists it as decision provenance, so
   * an explanation can be flagged stale when the model changes.
   */
  public Optional<String> embeddingModelId() {
    return Optional.ofNullable(embeddingModelId);
  }

  /** Number of vectors currently memoized (used by tests and diagnostics). */
  public int size() {
    synchronized (vectors) {
      return vectors.size();
    }
  }

  /** The memoized vector for {@code text}, or null. Never computes anything. */
  public float[] peek(String text) {
    synchronized (vectors) {
      return vectors.get(text);
    }
  }

  /**
   * Returns the memoized vector for {@code text}, computing it via
   * {@code loader} on a miss. Beyond {@link #MAX_ENTRIES} the value is computed
   * and returned without being stored, so the memo cannot grow without bound.
   */
  public float[] computeIfAbsent(String text, String modelId,
                          Function<String, float[]> loader) {
    synchronized (vectors) {
      float[] cached = vectors.get(text);
      if (cached != null) {
        return cached;
      }
    }

    float[] computed = loader.apply(text);

    synchronized (vectors) {
      if (embeddingModelId == null) {
        embeddingModelId = modelId;
      }
      if (vectors.size() < MAX_ENTRIES) {
        vectors.putIfAbsent(text, computed);
      }
    }
    return computed;
  }
}
