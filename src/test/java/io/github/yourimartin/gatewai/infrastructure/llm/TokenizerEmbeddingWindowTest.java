package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The window against the shipped tokenizer (128 tokens for
 * {@code paraphrase-multilingual-MiniLM-L12-v2}), loaded from the same resource
 * the application reads.
 */
class TokenizerEmbeddingWindowTest {

  private static final String TOKENIZER =
      "classpath:/onnx/paraphrase-multilingual-MiniLM-L12-v2/tokenizer.json";

  private TokenizerEmbeddingWindow window;

  @BeforeEach
  void setUp() {
    window = new TokenizerEmbeddingWindow(new DefaultResourceLoader(), TOKENIZER);
  }

  @AfterEach
  void tearDown() {
    window.close();
  }

  @Test
  void aShortQuestionFits() {
    assertTrue(window.fits("What is a B-tree?"));
    assertTrue(window.fits("Comment fonctionne un vaccin ?"));
  }

  @Test
  void aPromptPastTheWindowDoesNotFit() {
    String longPrompt = "Answer using only the context below. ".repeat(40);
    assertFalse(window.fits(longPrompt));
  }

  @Test
  void theBoundaryIsTheTokenizersWindow() {
    // One token per repeated word, plus the two special tokens: 60 words sit
    // inside the 128-token window, 200 run past it.
    assertTrue(window.fits("a ".repeat(60)));
    assertFalse(window.fits("a ".repeat(200)));
  }
}
