package io.github.yourimartin.gatewai.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * An {@link EmbeddingModel} that answers from recorded vectors (v2 batch 5).
 *
 * <p>This is what keeps {@code ./mvnw test} hermetic while still exercising the
 * production classifier: the classifier is real, its configuration is real, only
 * the model server is replaced by a lookup.
 *
 * <p>An unknown text is a hard failure, never a zero vector. A silently
 * fabricated embedding would score as a confident wrong answer and quietly
 * lower the reported accuracy of code that never changed.
 */
final class ReplayEmbeddingModel implements EmbeddingModel {

  private final Map<String, float[]> vectors;
  private final int dimensions;

  ReplayEmbeddingModel(Map<String, float[]> vectors, int dimensions) {
    this.vectors = Map.copyOf(vectors);
    this.dimensions = dimensions;
  }

  @Override
  public float[] embed(String text) {
    float[] vector = vectors.get(text);
    if (vector == null) {
      throw new IllegalStateException(
          "No recorded embedding for [" + text + "] — the fixtures are stale. Re-record: "
              + EvalPaths.RECORD_COMMAND);
    }
    return vector.clone();
  }

  @Override
  public float[] embed(Document document) {
    return embed(document.getText());
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<Embedding> embeddings = new ArrayList<>();
    List<String> instructions = request.getInstructions();
    for (int i = 0; i < instructions.size(); i++) {
      embeddings.add(new Embedding(embed(instructions.get(i)), i));
    }
    return new EmbeddingResponse(embeddings);
  }

  @Override
  public int dimensions() {
    return dimensions;
  }
}
