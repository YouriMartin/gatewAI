package io.github.yourimartin.gatewai.infrastructure.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.port.out.EmbeddingWindow;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * The embedding window, counted with the embedding model's own tokenizer
 * (ADR 0014, v4 A.2).
 *
 * <p>Loads the tokenizer the embedding model is built from
 * ({@code spring.ai.embedding.transformer.tokenizer.uri}) twice: as the model
 * uses it, with the truncation its {@code tokenizer.json} declares, and
 * untruncated. A text fits when both see the same number of tokens. The window
 * is therefore read from the tokenizer, never hard-coded: swap the embedding
 * model and the cache's exact-match rule follows it.
 */
@Component
final class TokenizerEmbeddingWindow implements EmbeddingWindow, AutoCloseable {

  private final HuggingFaceTokenizer windowed;
  private final HuggingFaceTokenizer full;

  TokenizerEmbeddingWindow(ResourceLoader resources,
                           @Value("${spring.ai.embedding.transformer.tokenizer.uri}")
                           String tokenizerLocation) {
    HuggingFaceTokenizer asTheModelUsesIt =
        load(resources, tokenizerLocation, Map.of("padding", "false"));
    boolean loaded = false;
    try {
      this.full = load(resources, tokenizerLocation,
          Map.of("padding", "false", "truncation", "false"));
      loaded = true;
    } finally {
      if (!loaded) {
        // Native memory: do not leak the first one when the second fails.
        asTheModelUsesIt.close();
      }
    }
    this.windowed = asTheModelUsesIt;
  }

  @Override
  public boolean fits(String text) {
    return windowed.encode(text).getIds().length == full.encode(text).getIds().length;
  }

  @Override
  @PreDestroy
  public void close() {
    windowed.close();
    full.close();
  }

  private static HuggingFaceTokenizer load(ResourceLoader resources, String location,
                                           Map<String, String> options) {
    try (InputStream in = resources.getResource(location).getInputStream()) {
      return HuggingFaceTokenizer.newInstance(in, options);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not load the embedding tokenizer from "
          + location, e);
    }
  }
}
