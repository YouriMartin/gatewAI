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

Test: `NativeRuntimeHintsTest` checks the registration via
`RuntimeHintsPredicates`.

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

Status: **native-ready** (config + hints + docs). Full-image validation to be
done on a GraalVM runner.
