# ADR 0004 — Scoped Values for context; avoid Structured Concurrency

**Status:** Accepted

## Context

The request's client/tenant id (and a chosen carbon zone for deferred jobs) must
reach the cache advisor and the green accounting **without threading a parameter
through every method**. Java 25 also offers Structured Concurrency, which is
tempting for parallel fan-out (e.g. reading per-zone carbon intensities).

## Decision

- Use **Scoped Values** (finalized in Java 25) for request context:
  `RequestContext.CURRENT` (clientId/trace) and `CarbonZoneContext.CURRENT` (zone).
  They pair cleanly with **Virtual Threads** (`spring.threads.virtual.enabled=true`).
- **Do not use Structured Concurrency** in the core — it is still a **preview**
  feature with an API that may change. Parallel work stays on plain
  loops / `ExecutorService` / `CompletableFuture`.

## Consequences

- Context is available anywhere in the call stack, immutably, without parameter
  plumbing or mutable `ThreadLocal`s.
- The core does not depend on a preview API, so it will not break on a JDK update
  that changes Structured Concurrency.
- If/when Structured Concurrency is finalized, it can be adopted for fan-out
  without affecting the context-propagation design.
