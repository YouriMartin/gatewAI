package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adapts Spring AI's {@code EmbeddingModel} to the {@link TextEmbedder} port
 * (v2 batch 3), so calibration can embed labelled text without the application
 * layer importing Spring AI.
 *
 * <p>Injected with the {@code @Primary} memoizing model, which is the right one:
 * a calibration run embeds each distinct text once anyway, and using the same
 * bean as the request path guarantees the vectors a threshold is fitted on are
 * the vectors it will be applied to.
 *
 * <p>{@code modelId} must name the model that produced those vectors: it is
 * stamped on every stored calibration and is what makes one **detectably stale**
 * when the model changes. It therefore reads the same
 * {@code gatewai.embedding.model-id} as {@link EmbeddingConfiguration} — v3
 * batch A.1 left it pointing at a deleted Ollama property, which stamped
 * {@code "unknown"} on both sides of the comparison and quietly made every
 * calibration look current forever.
 */
@Component
class SpringAiTextEmbedder implements TextEmbedder {

  private final EmbeddingModel embeddingModel;
  private final String modelId;

  SpringAiTextEmbedder(
      EmbeddingModel embeddingModel,
      @Value("${gatewai.embedding.model-id:unknown}") String modelId) {
    this.embeddingModel = embeddingModel;
    this.modelId = modelId;
  }

  @Override
  public float[] embed(String text) {
    return embeddingModel.embed(text);
  }

  @Override
  public String modelId() {
    return modelId;
  }
}
