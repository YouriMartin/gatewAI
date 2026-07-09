# Overview

gatewAI is an **open-source, self-hosted LLM gateway** for enterprises. It sits
between your applications and the LLM providers you use, and does four things on
every request: **secures**, **caches**, **routes**, and **measures the carbon
footprint** of AI traffic.

It speaks the **OpenAI API format** on the way in, so existing apps and SDKs work
by only changing the `base_url` — no code rewrite.

## The problem

Enterprises adopting LLMs hit three recurring problems:

- **Cost** — the same or near-identical prompts are sent again and again, each one
  billed in full. Premium models are used even for trivial requests.
- **Privacy** — prompts and data flow through third-party APIs with little
  control or visibility, which is a blocker in regulated or sensitive contexts.
- **Environmental impact** — there is no visibility into the energy and carbon
  cost of AI usage, and growing reporting obligations (e.g. CSRD) demand figures
  nobody currently produces.

gatewAI addresses all three in a single infrastructure component you host
yourself.

## How it helps

| Pillar | What gatewAI does | Outcome |
|---|---|---|
| **Cost** | A **semantic cache** short-circuits redundant requests before any model call; a **smart router** sends each request to the cheapest model that can handle it | Fewer and cheaper paid calls |
| **Privacy** | Fully **on-premise**: embeddings are computed locally (Ollama), provider keys live only in your deployment, data does not transit a SaaS gateway | Keys and prompts stay under your control |
| **Carbon** | **Green accounting** turns tokens into estimated kWh and gCO2, computes the **CO2 avoided** thanks to cache + routing, and exports **CSRD-friendly reports** | Quantified, reportable AI footprint |

## Who it is for

- **Platform / infrastructure teams** who want a drop-in, OpenAI-compatible proxy
  in front of multiple providers, with caching and routing built in.
- **Organizations with AI governance or CSRD reporting needs** that must measure
  and report the cost and carbon of AI.
- **Privacy-sensitive deployments** (on-premise, regulated industries) that cannot
  route data through a third-party gateway.
- **Developers** who want cost and carbon savings without touching application
  code — just repoint the `base_url`.

## Key vocabulary

- **Ingress** — the API format your clients speak to gatewAI (OpenAI-compatible,
  `/v1/chat/completions`). Also exposed over **MCP** (Model Context Protocol).
- **Egress** — the actual provider gatewAI calls behind the scenes: local-first
  by default (Ollama models, zero API keys), with any configured mix of provider
  instances — more Ollama/vLLM servers, Anthropic, OpenAI, any OpenAI-compatible
  endpoint. Ingress and egress are independent.
- **Semantic cache hit / miss** — a *hit* means a semantically similar prompt was
  already answered and the stored answer is returned without calling any model; a
  *miss* goes through to a model and the answer is cached.
- **Tier** — the routing classes a request can fall into: `LOCAL`, `CLOUD_ENTRY`,
  `CLOUD_PREMIUM`. The router picks the cheapest tier that can answer correctly.
- **Avoided CO2 (and avoided €)** — the difference between what a
  premium-by-default call *would* have emitted/cost and what actually happened
  after caching and routing. This is the headline savings figure.
- **Grid carbon intensity** — the gCO2 emitted per kWh of electricity for a given
  zone, used to convert estimated energy into emissions.
- **CSRD** — the EU Corporate Sustainability Reporting Directive; the reporting
  exports are designed to feed this kind of disclosure.

## What it is not

gatewAI is a **thin gateway**, not a model server. It does not host or serve
models itself — it routes to providers (cloud APIs or a local Ollama). It is not
a training or fine-tuning platform.

For honest, detailed boundaries see [`limitations.md`](limitations.md). For the
reasoning behind the functional design see
[`functional-choices.md`](functional-choices.md). To try it now, see
[`getting-started.md`](getting-started.md).
