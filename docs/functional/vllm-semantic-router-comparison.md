# Inspiration: vLLM Semantic Router, and how gatewAI differs

gatewAI's routing-and-caching idea is inspired in part by **vLLM Semantic Router**
(`vllm-project/semantic-router`), an intelligent "Mixture-of-Models" router for
LLM traffic. This page credits that inspiration and is honest about where gatewAI
is similar, where it differs, and where it is simply narrower.

> **Caveat:** this describes vLLM Semantic Router based on its public description
> at the time of writing. It is an active project — verify its current
> capabilities upstream before quoting specifics.

## What vLLM Semantic Router is (in brief)

A semantic, intent-aware router that sits in front of an OpenAI-compatible model
pool and picks the most suitable model per request. Notable traits:

- Runs as an **Envoy External Processor (ext_proc)** in the Envoy / AI-Gateway data
  plane — designed for serving infrastructure (Kubernetes, high throughput).
- Uses a **fine-tuned BERT-class classifier** for category/intent classification to
  route across a pool of models ("Mixture of Models"), optimizing accuracy and
  cost/latency.
- Provides **semantic caching** to skip recomputation.
- Adds **enterprise guardrails**: PII detection, prompt-guard / jailbreak
  detection, and features like reasoning-mode control and tool selection.
- Implemented largely in **Go + Rust**.

## What gatewAI borrowed

- The **core idea**: route requests *semantically* to the cheapest/most suitable
  model rather than sending everything to one premium model.
- **Semantic caching** in front of an **OpenAI-compatible** ingress.
- The notion that a gateway is the right layer to centralize these
  cross-cutting concerns.

## Where gatewAI is different

| Dimension | vLLM Semantic Router | gatewAI |
|---|---|---|
| **Primary goal** | Accuracy-oriented model selection + enterprise guardrails | **Green (carbon) + cost + CSRD reporting** |
| **Headline metric** | Routing accuracy / cost-latency efficiency | **€ saved and gCO2 avoided**, with CSRD export |
| **Form factor** | Envoy `ext_proc` in a serving data plane | **Standalone thin gateway** (one Spring Boot process) |
| **Stack** | Go + Rust, Envoy / Kubernetes | **Java 25 / Spring Boot 4 / Spring AI**, advisor chain |
| **Classifier** | Fine-tuned BERT category model | **Heuristics by default**, optional small-LLM structured-output classifier, hot-configurable |
| **Caching** | Semantic cache | Semantic cache on **pgvector**, **local embeddings** (Ollama), **per-client namespacing** |
| **Guardrails** | PII detection, prompt guard, tool selection, reasoning control | **None of these (yet)** |
| **Carbon awareness** | Not a focus | **Carbon model, real-time grid intensity, avoided-CO2, temporal/geo-aware deferral** |
| **Reporting/UI** | Routing/observability metrics | **Green dashboard + CSV/PDF CSRD reports** |
| **MCP** | — | **Exposes itself as an MCP server** (assistants can drive it) |
| **Scale target** | Production serving scale | **Single-instance, portfolio-grade** |

## Honest positioning

- vLLM Semantic Router is a **heavier, serving-grade** component with a trained
  classifier and security guardrails, aimed at production model-serving
  infrastructure. It is more capable on **accuracy** and **safety** features.
- gatewAI is a **lighter, narrower** gateway whose distinctive value is the
  **green/cost/CSRD** lens, on-premise privacy, an OpenAI **and** MCP ingress, and a
  clean Spring AI advisor architecture — not feature parity with vLLM SR.
- They are not strictly competitors: a fair summary is "**same family of idea
  (semantic routing + caching), different intent**". gatewAI trades breadth and
  scale for a focused, reportable sustainability story.

## What gatewAI would need to close the gap

If the goal were to approach vLLM SR's capabilities, the natural additions would
be: a trained classifier for better routing accuracy, guardrails (PII / jailbreak
detection), a real multi-model/multi-provider pool wired by default, and a
cluster-ready runtime. See [`limitations.md`](limitations.md) for the current
boundaries.
