# ADR 0002 — Thin gateway over a Spring AI advisor chain

**Status:** Accepted

## Context

The three capabilities (semantic cache, routing, green accounting) could be built
as separate services or as bespoke middleware. The cache in particular must be able
to **block** a request before it reaches the model.

## Decision

Build a **thin gateway** around Spring AI's **Advisors API**
(`CallAdvisor`/`StreamAdvisor`). The semantic cache and the router are advisors;
the cache short-circuits by **not calling** `chain.nextCall()`. Green accounting is
kept at the **application-service layer** (after the model call) rather than as an
advisor, because it only needs the final response.

## Consequences

- The advisor API is purpose-built for read/modify/**block** of a request, which is
  exactly the cache's behaviour — no custom interception machinery.
- Ordering is explicit via `getOrder()`: cache (`HIGHEST_PRECEDENCE`) runs before
  routing (`+1`).
- Each concern is isolated and unit-testable in isolation.
- Honest nuance: the "three advisors" mental model is really **two advisors + one
  service-level step**; the docs state this so the code matches the description.
- Streaming is implemented on both advisors (Phase 7.5): `adviseStream` reroutes
  the prompt like the `call` path, and a cache hit is replayed as a **synthetic
  `Flux`** — the client gets the streaming UX with no model call. The store side
  captures `clientId` eagerly, because `doOnComplete` runs on a reactive thread
  where the Scoped Value is unbound.
