# GraalVM native image (Phase 6.3)

Native compilation is **optional**. The double win fits the green narrative:
**startup in tens of ms** and **sharply reduced memory footprint** vs the JVM —
fewer resources = less energy.

## Builds

`spring-boot-starter-parent` provides the `native` profile (AOT + GraalVM). Two
paths:

```bash
# 1. Local native executable — requires a GraalVM JDK (e.g. liberica-nik)
./mvnw -Pnative native:compile
./target/gatewai

# 2. Native container via buildpacks — no local GraalVM required
./mvnw -Pnative spring-boot:build-image
docker run --rm -p 8080:8080 gatewai:0.0.1-SNAPSHOT
```

> The native build first runs `process-aot` then `native:compile`: expect
> several minutes and a lot of RAM. The `frontend` profile (active by default)
> bundles the dashboard into the binary; the `static/**` resources are included
> by Spring Boot's native hints.

## Runtime hints (reflection)

AOT covers most of it, but a few types (de)serialized by reflection are declared
explicitly:

| Type | Why | Where |
|---|---|---|
| Web DTOs (OpenAI, admin, reports…) | controller Jackson binding | `NativeRuntimeHints` (`@ImportRuntimeHints`) |
| `ClassificationResult` | Spring AI Structured Output | `@RegisterReflectionForBinding` on `ChatClientConfiguration` |
| `ElectricityMapsResponse` | RestClient body | `@RegisterReflectionForBinding` on `CarbonConfiguration` |
| The ONNX model, both JNI libraries, `ai.onnxruntime.*` | in-process embeddings (v3 lot A) | `EmbeddingNativeRuntimeHints` (`@ImportRuntimeHints` on `EmbeddingConfiguration`) |

Tests: `NativeRuntimeHintsTest` and `EmbeddingNativeRuntimeHintsTest` check the
registrations via `RuntimeHintsPredicates`.

### The embedding model in a closed world (v3 lot A)

Three things the image needs that no static analysis can find:

- **the model** — `onnx/**`, named by a `classpath:` URI read from
  configuration, so nothing in the bytecode mentions it;
- **ONNX Runtime's JNI libraries** — `ai/onnxruntime/native/**`, extracted from
  the library's own jar at first use;
- **DJL's tokenizer library** — `native/lib/**`, extracted the same way. DJL's
  `api` jar ships `META-INF/native-image` metadata (including
  `--initialize-at-run-time` for its engine); the `tokenizers` jar does **not**,
  which is why that pattern is declared here.

Two consequences to plan for:

- **The binary carries the model**: registering `onnx/**` embeds ~130 MB.
  A deployment that would rather not can point
  `spring.ai.embedding.transformer.onnx.model-uri` and `…tokenizer.uri` at
  `file:` paths and ship the model beside the binary.
- **JNI reachability is declared, not proven.** The `ai.onnxruntime` types the
  runtime instantiates from native code are registered, but only a real GraalVM
  build can show the set is complete. Treat the first native run as the test.

## Caveats to validate in a GraalVM CI

The full native compilation is **not** run here (no GraalVM in the dev
environment). To verify in a dedicated CI:

- **OpenPDF** (PDF export) loads fonts/resources by reflection; the native image
  may need extra resource hints (`com/lowagie/text/pdf/fonts/**`). Otherwise PDF
  export may fail at native runtime while JSON/CSV work.
- **Hibernate/JPA**: `process-aot` refreshes the context → the database must be
  reachable at build time (or use a build profile without a DataSource).
- Add `org.graalvm.buildtools:native-maven-plugin` reachability metadata
  (already wired by the parent via `add-reachability-metadata`).
- **ONNX Runtime + DJL under GraalVM** (v3 lot A) is the newest unknown: the
  hints above cover resources and the JNI-facing types, but native libraries
  loaded through `System.load` after extraction are exactly the case that tends
  to need `--initialize-at-run-time` tuning. If the image builds but the first
  embedding fails, start there. The JVM path is unaffected either way.

Status: **native-ready** (config + hints + docs). Full-image validation to be
done on a GraalVM runner — unchanged by v3 lot A, which added hints and a test
but no claim that the image works.
