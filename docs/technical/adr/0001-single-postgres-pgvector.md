# ADR 0001 — One PostgreSQL (pgvector) for cache and metrics

**Status:** Accepted

## Context

The gateway needs a **vector store** (semantic cache) and a **relational store**
(request metrics, API clients). A common design uses a dedicated vector database
(e.g. Qdrant) plus a relational database — two systems to run, secure, back up and
keep alive in dev.

## Decision

Use **one PostgreSQL** with the **pgvector** extension for both: the vector cache
and the relational tables (`request_log`, `api_client`) live in the same database.

## Consequences

- **Fewer moving parts** — one container, one connection, one backup story; less
  RAM in dev, consistent with the green stance.
- pgvector with an HNSW + cosine index is more than enough for the expected cache
  size (768-dim vectors, well within pgvector limits).
- The vector store is used **only through Spring AI's `VectorStore` interface**
  (see [ADR 0005](0005-depend-on-vectorstore-interface.md)), so scaling out to a
  dedicated vector DB later is a dependency/config change, not a rewrite.
- Trade-off: at very high vector volume/throughput a specialized engine would
  outperform pgvector — accepted, and reversible.
