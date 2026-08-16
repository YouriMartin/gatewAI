# Architecture Decision Records

Short records of the structuring decisions, in the classic
*Context → Decision → Consequences* form. They capture the **why**, which is the
part that ages well.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-single-postgres-pgvector.md) | One PostgreSQL (pgvector) for cache **and** metrics | Accepted |
| [0002](0002-thin-gateway-advisor-chain.md) | Thin gateway over a Spring AI advisor chain | Accepted |
| [0003](0003-ingress-egress-separation.md) | Separate ingress (OpenAI/MCP) from egress (provider) | Accepted |
| [0004](0004-scoped-values-no-structured-concurrency.md) | Scoped Values for context; avoid Structured Concurrency | Accepted |
| [0005](0005-depend-on-vectorstore-interface.md) | Depend on the `VectorStore` interface, not pgvector | Accepted |
| [0006](0006-avoided-co2-premium-baseline.md) | Measure savings as "avoided" vs a premium baseline | Accepted |
| [0007](0007-memoized-embedding-model.md) | Share the request embedding by memoizing `EmbeddingModel` | Accepted |
| [0008](0008-conformal-prediction-over-fixed-thresholds.md) | Calibrate thresholds by conformal prediction, not tuning or Platt scaling | Accepted |
| [0009](0009-occlusion-over-gradient-attribution.md) | Explain a route match by occlusion, not by gradients | Accepted |
| [0010](0010-trace-cache-decisions-like-routing.md) | Trace cache decisions at the same level as routing decisions | Accepted |
