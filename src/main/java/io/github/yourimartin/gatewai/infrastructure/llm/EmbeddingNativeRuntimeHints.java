package io.github.yourimartin.gatewai.infrastructure.llm;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints for the in-process embedding model (v3 batch A.5).
 *
 * <p>Three kinds of resource have to survive the closed world, and none of them
 * is reachable by static analysis:
 *
 * <ul>
 *   <li><b>the model itself</b> — {@code onnx/**}, loaded through a
 *       {@code classpath:} URI read from configuration, so nothing in the
 *       bytecode names it;</li>
 *   <li><b>ONNX Runtime's JNI libraries</b> — {@code ai/onnxruntime/native/**},
 *       which the library extracts from its own jar at first use;</li>
 *   <li><b>DJL's tokenizer library</b> — {@code native/lib/**}, extracted the
 *       same way. DJL's {@code api} jar ships its own
 *       {@code META-INF/native-image} metadata; the {@code tokenizers} jar does
 *       not, which is why this one is ours to declare.</li>
 * </ul>
 *
 * <p>Two consequences worth stating rather than discovering:
 *
 * <ul>
 *   <li><b>The binary carries the model.</b> Registering {@code onnx/**} embeds
 *       ~130 MB into the image. A deployment that would rather not can point
 *       {@code spring.ai.embedding.transformer.onnx.model-uri} and
 *       {@code …tokenizer.uri} at {@code file:} paths and ship the model beside
 *       the binary — the hint then costs nothing and the resource is simply
 *       absent.</li>
 *   <li><b>This is declared, not validated.</b> JNI callbacks into
 *       {@code ai.onnxruntime} are registered below on the classes the runtime
 *       instantiates from native code, but only a real GraalVM build proves the
 *       set is complete. Native status stays <i>native-ready, not validated</i>
 *       (see {@code docs/technical/native.md}).</li>
 * </ul>
 */
class EmbeddingNativeRuntimeHints implements RuntimeHintsRegistrar {

  /** Classes ONNX Runtime instantiates or calls back into from JNI. */
  private static final String[] ONNX_JNI_TYPES = {
      "ai.onnxruntime.OnnxTensor",
      "ai.onnxruntime.OnnxSequence",
      "ai.onnxruntime.OnnxMap",
      "ai.onnxruntime.OrtEnvironment",
      "ai.onnxruntime.OrtSession",
      "ai.onnxruntime.OrtSession$Result",
      "ai.onnxruntime.OrtSession$SessionOptions",
      "ai.onnxruntime.OrtException",
      "ai.onnxruntime.TensorInfo",
      "ai.onnxruntime.MapInfo",
      "ai.onnxruntime.SequenceInfo",
      "ai.onnxruntime.OnnxJavaType",
  };

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.resources()
        .registerPattern("onnx/*/*")
        .registerPattern("ai/onnxruntime/native/*")
        .registerPattern("ai/onnxruntime/native/*/*")
        .registerPattern("native/lib/*")
        .registerPattern("native/lib/*/*")
        .registerPattern("native/lib/*/*/*")
        .registerPattern("native/lib/tokenizers.properties");

    for (String type : ONNX_JNI_TYPES) {
      hints.reflection().registerTypeIfPresent(classLoader, type,
          MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
          MemberCategory.INVOKE_DECLARED_METHODS,
          MemberCategory.ACCESS_DECLARED_FIELDS);
    }

    // The Spring AI adapter is created reflectively by its auto-configuration.
    hints.reflection().registerTypeIfPresent(classLoader,
        "org.springframework.ai.transformers.TransformersEmbeddingModel",
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
        MemberCategory.INVOKE_DECLARED_METHODS);
  }
}
