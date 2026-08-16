package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.transformers.autoconfigure.TransformersEmbeddingModelAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/**
 * The embedding model runs <b>in the JVM</b> (v3 lot A): no Ollama, no HTTP, no
 * download. This test is the claim itself — it boots the transformers
 * auto-configuration with the shipped properties and embeds text, with nothing
 * listening on any port.
 *
 * <p>It is an ordinary unit test on purpose. Before lot A the same assertions
 * lived in {@code EmbeddingModelSmokeTest}, tagged {@code integration} because
 * they needed a model server; the whole point of the batch is that they no
 * longer do.
 */
class InProcessEmbeddingModelTest {

  /** Below this, a file is a Git LFS pointer or a truncated download. */
  private static final long PLAUSIBLE_MODEL_BYTES = 1_000_000L;

  private static final int EXPECTED_DIMENSIONS = 384;

  private final Properties shipped = shippedProperties();

  @Test
  @DisplayName("the committed model is a model, not an LFS pointer")
  void bundledResourcesAreRealFiles() throws IOException {
    for (String key : new String[] {
        "spring.ai.embedding.transformer.onnx.model-uri",
        "spring.ai.embedding.transformer.tokenizer.uri"}) {
      String uri = shipped.getProperty(key);
      assertNotNull(uri, key + " must be configured");
      assertTrue(uri.startsWith("classpath:"),
          key + " must point inside the jar, not at a URL: " + uri);

      ClassPathResource resource =
          new ClassPathResource(uri.substring("classpath:".length()));
      assertTrue(resource.exists(),
          "missing model resource: " + uri + ". It is fetched at build time, not "
              + "committed — run `./mvnw generate-resources`.");
      assertTrue(resource.contentLength() > PLAUSIBLE_MODEL_BYTES,
          uri + " is only " + resource.contentLength() + " bytes — a truncated or "
              + "placeholder file, not the model.");
    }
  }

  @Test
  @DisplayName("embeds at 384 dimensions with no server reachable")
  void embedsWithoutAnyModelServer() {
    runner().run(context -> {
      EmbeddingModel model = context.getBean(EmbeddingModel.class);

      float[] vector = model.embed("Hello, world!");

      assertEquals(EXPECTED_DIMENSIONS, vector.length);
      assertEquals(EXPECTED_DIMENSIONS, model.dimensions());
    });
  }

  @Test
  @DisplayName("the @Primary bean is the memoizing decorator, not the raw model")
  void primaryBeanIsTheMemoizingDecorator() {
    runner().run(context ->
        assertTrue(context.getBean(EmbeddingModel.class) instanceof MemoizingEmbeddingModel,
            "ADR 0007's memo must still wrap whatever computes the vectors"));
  }

  @Test
  @DisplayName("unrelated texts do not collapse onto the same vector")
  void differentTextsProduceDifferentEmbeddings() {
    runner().run(context -> {
      EmbeddingModel model = context.getBean(EmbeddingModel.class);

      assertFalse(java.util.Arrays.equals(
          model.embed("Java virtual threads improve server scalability."),
          model.embed("The best recipe for chocolate cake involves cocoa powder.")));
    });
  }

  @Test
  @DisplayName("a French prompt is closer to its English paraphrase than to an unrelated one")
  void embedsTheTwoLanguagesTheDefaultRoutesAreWrittenIn() {
    runner().run(context -> {
      EmbeddingModel model = context.getBean(EmbeddingModel.class);

      float[] french = model.embed("Résume ce texte en trois phrases.");
      float[] englishParaphrase = model.embed("Summarize this text in three sentences.");
      float[] englishUnrelated = model.embed("Refactor this Java service to use records.");

      double toParaphrase = cosine(french, englishParaphrase);
      double toUnrelated = cosine(french, englishUnrelated);

      // The reason a multilingual model was chosen: the default routes carry
      // bilingual examples, and cross-language matching is what makes them work.
      assertTrue(toParaphrase > toUnrelated,
          "cross-lingual similarity " + toParaphrase
              + " should beat the unrelated pair " + toUnrelated);
    });
  }

  /**
   * The auto-configuration under the shipped properties — the same wiring
   * {@code application.properties} produces, minus the database and the web
   * layer. Reading the values from the shipped file rather than restating them
   * is what makes a renamed model directory fail here instead of in production.
   */
  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(TransformersEmbeddingModelAutoConfiguration.class))
        .withUserConfiguration(EmbeddingConfiguration.class)
        .withPropertyValues(
            "spring.ai.embedding.transformer.onnx.model-uri="
                + shipped.getProperty("spring.ai.embedding.transformer.onnx.model-uri"),
            "spring.ai.embedding.transformer.tokenizer.uri="
                + shipped.getProperty("spring.ai.embedding.transformer.tokenizer.uri"),
            "spring.ai.embedding.transformer.tokenizer.options.padding=true",
            "gatewai.embedding.model-id="
                + shipped.getProperty("gatewai.embedding.model-id"));
  }

  private static Properties shippedProperties() {
    Properties properties = new Properties();
    try (InputStream in =
             InProcessEmbeddingModelTest.class.getResourceAsStream("/application.properties")) {
      assertNotNull(in, "application.properties must be on the test classpath");
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return properties;
  }

  private static double cosine(float[] a, float[] b) {
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }
}
