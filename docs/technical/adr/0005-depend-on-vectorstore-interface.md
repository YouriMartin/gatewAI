# ADR 0005 — Depend on the `VectorStore` interface, not pgvector

**Status:** Accepted

## Context

The semantic cache stores and searches embeddings. pgvector is the chosen backend
([ADR 0001](0001-single-postgres-pgvector.md)), but vector workloads can outgrow a
relational engine, and lock-in to a specific store would be costly to undo.

## Decision

The cache (`SemanticCacheAdvisor`) depends **only** on Spring AI's `VectorStore`
abstraction — `similaritySearch`, `add`, `Filter.Expression` — never on
pgvector-specific APIs.

## Consequences

- Switching to Qdrant (or another Spring AI vector store) is a **dependency +
  config** change; the cache logic is untouched.
- The cache code is trivially **mockable** in unit tests (`SemanticCacheAdvisorTest`
  uses a mock `VectorStore`).
- Cost: we cannot use pgvector-only features in the cache path — an acceptable
  constraint for reversibility.
