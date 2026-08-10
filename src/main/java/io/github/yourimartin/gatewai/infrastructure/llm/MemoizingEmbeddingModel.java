package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.RequestEmbeddingMemo;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * {@code EmbeddingModel} decorator that memoizes single-text embeddings for the
 * duration of one request (v2 batch 0.2).
 *
 * <p>Registered as the {@code @Primary} {@code EmbeddingModel}, so both the
 * pgvector store (which embeds the query internally, via
 * {@code PgVectorStore.getQueryEmbedding}) and
 * {@link EmbeddingComplexityClassifier} are injected with it. Both call
 * {@link #embed(String)} with the same user text, so an uncached request now
 * embeds once instead of twice — without any component being aware of the
 * sharing, and without reaching around the {@code VectorStore} interface.
 *
 * <p>An uncached request actually embeds the user text <b>three</b> times, not
 * twice: the cache searches with it, the router classifies it, and then the
 * cache <i>stores</i> the answer under {@code new Document(userText, …)}, which
 * embeds it once more. The single-document case of
 * {@link #embed(List, EmbeddingOptions, BatchingStrategy)} therefore reads the
 * memo too, which brings the request down to one embedding call.
 *
 * <p>Reusing a query vector as a document vector is exact here, not an
 * approximation: nothing in this gateway sets per-call embedding options, and
 * the Ollama model applies no query/document prefix, so both paths ask the same
 * model for the same text. Multi-document calls (route-example indexing) are
 * delegated untouched — they have their own cache and would only pollute the
 * memo.
 */
class MemoizingEmbeddingModel implements EmbeddingModel {

  private final EmbeddingModel delegate;
  private final String modelId;

  MemoizingEmbeddingModel(EmbeddingModel delegate, String modelId) {
    this.delegate = delegate;
    this.modelId = modelId;
  }

  /** Identifier of the underlying embedding model (provenance for batch 2). */
  String modelId() {
    return modelId;
  }

  @Override
  public float[] embed(String text) {
    return RequestEmbeddingMemo.current()
        .map(memo -> memo.computeIfAbsent(text, modelId, delegate::embed))
        .orElseGet(() -> delegate.embed(text));
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    return delegate.call(request);
  }

  @Override
  public float[] embed(Document document) {
    return delegate.embed(document);
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    return delegate.embed(texts);
  }

  @Override
  public List<float[]> embed(List<Document> documents,
                             @Nullable EmbeddingOptions options,
                             BatchingStrategy batchingStrategy) {
    // The cache stores its miss as a single document holding the very text it
    // just searched with, so that vector is already in the memo.
    if (documents.size() == 1) {
      String text = getEmbeddingContent(documents.getFirst());
      float[] memoized = text == null ? null : memoized(text);
      if (memoized != null) {
        return List.of(memoized);
      }
    }
    return delegate.embed(documents, options, batchingStrategy);
  }

  /** The already-computed vector for {@code text}, or null — never computes. */
  private float[] memoized(String text) {
    return RequestEmbeddingMemo.current()
        .map(memo -> memo.peek(text))
        .orElse(null);
  }

  @Override
  public EmbeddingResponse embedForResponse(List<String> texts) {
    return delegate.embedForResponse(texts);
  }

  @Override
  public @Nullable String getEmbeddingContent(Document document) {
    return delegate.getEmbeddingContent(document);
  }

  @Override
  public int dimensions() {
    return delegate.dimensions();
  }
}
