package io.github.yourimartin.gatewai.infrastructure.llm;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Primary;

/**
 * Wraps the embedding model in {@link MemoizingEmbeddingModel} (v2 batch 0.2).
 *
 * <p>The delegate is injected by its concrete {@code TransformersEmbeddingModel}
 * type on purpose: it disambiguates from the {@code @Primary} bean defined here
 * (an {@code EmbeddingModel} parameter would be self-referential), and it states
 * the actual constraint — since v3 lot A embeddings run <b>in-process</b> (DJL +
 * ONNX Runtime, model and tokenizer bundled as classpath resources), so the
 * semantic cache, the semantic routes and the explanation services depend on no
 * model server and no vendor. Ollama remains the default chat <i>egress</i>.
 *
 * <p>The concrete model is built by Spring AI's auto-configuration from
 * {@code spring.ai.embedding.transformer.*}; only the memoizing decorator is
 * ours. {@code gatewai.embedding.model-id} names the model for provenance —
 * it is stamped on every decision row, every calibration and every explanation,
 * which is what makes them detectably stale when the model changes.
 */
@Configuration
@ImportRuntimeHints(EmbeddingNativeRuntimeHints.class)
class EmbeddingConfiguration {

  @Bean
  @Primary
  EmbeddingModel memoizingEmbeddingModel(
      TransformersEmbeddingModel delegate,
      @Value("${gatewai.embedding.model-id:unknown}") String embeddingModelId) {
    return new MemoizingEmbeddingModel(delegate, embeddingModelId);
  }
}
