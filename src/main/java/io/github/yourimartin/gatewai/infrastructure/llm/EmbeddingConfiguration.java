package io.github.yourimartin.gatewai.infrastructure.llm;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wraps the embedding model in {@link MemoizingEmbeddingModel} (v2 batch 0.2).
 *
 * <p>The delegate is injected by its concrete {@code OllamaEmbeddingModel} type
 * on purpose: it disambiguates from the {@code @Primary} bean defined here (an
 * {@code EmbeddingModel} parameter would be self-referential), and it states the
 * actual constraint — embeddings are <b>always</b> local Ollama in this gateway,
 * the OpenAI embedding auto-config being explicitly excluded so that the
 * semantic cache and the router never depend on a vendor.
 */
@Configuration
class EmbeddingConfiguration {

  @Bean
  @Primary
  EmbeddingModel memoizingEmbeddingModel(
      OllamaEmbeddingModel delegate,
      @Value("${spring.ai.ollama.embedding.options.model:unknown}")
      String embeddingModelId) {
    return new MemoizingEmbeddingModel(delegate, embeddingModelId);
  }
}
