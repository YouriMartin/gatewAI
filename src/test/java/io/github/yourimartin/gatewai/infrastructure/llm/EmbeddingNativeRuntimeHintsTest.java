package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * The in-process embedding model reaches the native image only through hints:
 * the model is named by configuration and the two JNI libraries are extracted
 * from their own jars at runtime, so static analysis sees none of the three
 * (v3 batch A.5).
 *
 * <p>These assertions prove the hints are <b>declared</b>. They cannot prove the
 * image works — that needs a GraalVM build, which stays a dedicated job (see
 * {@code docs/technical/native.md}).
 */
class EmbeddingNativeRuntimeHintsTest {

  private final RuntimeHints hints = registeredHints();

  @Test
  @DisplayName("the configured model resources are registered, by their real path")
  void registersTheBundledModel() {
    // Read from the shipped configuration rather than restated: renaming the
    // model directory must fail here, not in a native build nobody runs daily.
    for (String key : new String[] {
        "spring.ai.embedding.transformer.onnx.model-uri",
        "spring.ai.embedding.transformer.tokenizer.uri"}) {
      String uri = shipped().getProperty(key);
      assertNotNull(uri, key + " must be configured");
      String path = uri.substring("classpath:/".length());

      assertTrue(RuntimeHintsPredicates.resource().forResource(path).test(hints),
          path + " is not covered by a resource hint — a native image would "
              + "start and then fail to load the embedding model");
    }
  }

  @Test
  @DisplayName("the JNI libraries both runtimes extract are registered")
  void registersTheNativeLibraries() {
    assertTrue(RuntimeHintsPredicates.resource()
        .forResource("ai/onnxruntime/native/linux-x64/libonnxruntime.so").test(hints));
    assertTrue(RuntimeHintsPredicates.resource()
        .forResource("ai/onnxruntime/native/linux-x64/libonnxruntime4j_jni.so").test(hints));
    // DJL's api jar ships its own metadata; the tokenizers jar does not.
    assertTrue(RuntimeHintsPredicates.resource()
        .forResource("native/lib/linux-x86_64/cpu/libtokenizers.so").test(hints));
    assertTrue(RuntimeHintsPredicates.resource()
        .forResource("native/lib/tokenizers.properties").test(hints));
  }

  @Test
  @DisplayName("ONNX Runtime types called back from JNI are reflectively reachable")
  void registersOnnxJniTypes() {
    for (String type : new String[] {
        "ai.onnxruntime.OnnxTensor", "ai.onnxruntime.OrtSession",
        "ai.onnxruntime.OrtEnvironment", "ai.onnxruntime.TensorInfo"}) {
      assertTrue(RuntimeHintsPredicates.reflection().onType(typeOf(type)).test(hints),
          type + " must be registered: ONNX Runtime instantiates it from native code");
    }
  }

  private static RuntimeHints registeredHints() {
    RuntimeHints hints = new RuntimeHints();
    new EmbeddingNativeRuntimeHints()
        .registerHints(hints, EmbeddingNativeRuntimeHintsTest.class.getClassLoader());
    return hints;
  }

  private static org.springframework.aot.hint.TypeReference typeOf(String name) {
    return org.springframework.aot.hint.TypeReference.of(name);
  }

  private static Properties shipped() {
    Properties properties = new Properties();
    try (InputStream in = EmbeddingNativeRuntimeHintsTest.class
        .getResourceAsStream("/application.properties")) {
      assertNotNull(in, "application.properties must be on the test classpath");
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return properties;
  }
}
