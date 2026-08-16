# Decision tracing and replay (v2 batches 2, 9)

Every request the gateway decides about leaves a row saying what it decided and
why. This page covers the model, what versioning it, and how it is read back.
Schema in [`data-model.md`](data-model.md), endpoints in
[`api-reference.md`](api-reference.md).

## What is recorded, and why both

Two tables, one per decision the gateway takes on the request path:

- **`routing_decision`** — the tier, the model, the reason, the justification,
  the confidence, the latency;
- **`cache_decision`** — the outcome, the similarity, the runner-up, the
  threshold, the served entry.

The cache is traced at the same level as routing, which the plan for v2 argued
about and then settled on cost asymmetry: a routing mistake sends a request to a
model that is too expensive or too weak, and costs money; a cache false positive
answers a **different question**, and costs trust. The decision that can be
wrong in the worse way must not be the one nobody can inspect
([ADR 0010](adr/0010-trace-cache-decisions-like-routing.md)).

Writing never blocks and never throws (`AsyncDecisionRecorder`): a trace exists
to explain requests, never to fail them. Failures are counted as
`gatewai.decisions.write.failures`, so silence is visible rather than silent.

**No plaintext prompt is stored** — `prompt_hash` and `prompt_length` only. That
choice is load-bearing everywhere below.

## Versioning: what a row is true of

A decision is only meaningful relative to the rules that produced it, and those
rules are editable in production. Three columns carry that context:

| Column | What changing it invalidates |
|---|---|
| `routing_config_version` | the route set, thresholds and keywords — a hash of `RoutingConfig`, order-insensitive where it should be |
| `embedding_model` | every similarity in the row |
| `conformal_alpha` (+ the calibration's own provenance) | the prediction set the decision was taken under |

Without them a decision read a month later would be explained by *today's*
configuration — the failure mode this whole batch exists to prevent. The same
reasoning keys the attribution cache (see
[`attribution.md`](attribution.md)) and stales a calibration
([`conformal-calibration.md`](conformal-calibration.md)).

Deliberately **not** versioned: `gatewai.classifier.cascade-margin-band`. It
changes no similarity, so making it bump the version would force a
recalibration for a knob the calibration does not depend on
([`routing.md`](routing.md)).

## Reading it back

`DecisionHistory` is a **separate port** from `DecisionRecorder`, because the two
have opposite contracts: writing must never throw, reading is an admin query
whose failure is worth surfacing.

The history is **merged across both tables**. A cache hit short-circuits the
advisor chain and never reaches the router, so a list built on routing rows alone
would omit exactly the requests the cache answered. Merging costs two bounded
queries plus two more for the halves the windows did not line up on.

A row with no correlation id still appears, keyed by its own row id: dropping it
would make the history quietly incomplete, and merging all of them together
would invent a request that never existed.

## Replay: what survives, and what does not

`POST /v1/admin/decisions/explain` takes **either** a correlation id **or** a
prompt, and the two answer different questions:

| Input | What comes back | Why |
|---|---|---|
| `correlationId` | the stored trace, in full | it was recorded |
| | attribution and counterfactuals as `PROMPT_UNAVAILABLE` | occlusion and route ranking both need to **re-embed the text**, and only its hash was kept |
| `prompt` | attribution and counterfactuals, computed now | the text is in hand |
| | no decision | none was taken — claiming one would describe a request that never happened |

This is the privacy property doing what it says, not a gap. Opt-in plaintext
prompt storage with retention is an open v2 decision (default off); until then,
"replay" means *the decision replays exactly, the analysis is recomputed*, and
`provenance` is what tells the reader which of the two they are looking at.

The route scores the decision was actually taken with **do** survive — they are
in the justification on the row. What cannot be rebuilt is the comparison
against today's routes.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `gatewai.decisions.enabled` | `true` | switches recording off without touching the metrics, which are fed by a sibling port |
| `gatewai.decisions.retention-days` | `90` | a scheduled worker purges older rows |

An explanation that 404s on a request you know happened is usually one of these
two: recording off, or retention passed. The 404 says so.

## What it is not

- **Not an audit log.** Rows are purged on a retention timer and carry no
  actor, only a client id on the carbon record.
- **Not a certification.** The trace supports the transparency angle of the EU
  AI Act for a component that routes and caches; gatewAI is infrastructure and
  claims no compliance of its own. See the note below.
- **Not the carbon record.** The explanation *references* it by correlation id;
  the figures live in `request_log` and in the green report, and duplicating
  them is how two sources of truth start disagreeing.

## Compliance note (v2 batch 10)

### What is logged

| Store | Holds | Prompt text? |
|---|---|---|
| `request_log` | correlation id, model, `prompt_hash`, token counts, latency, client id, cost/energy/CO2 | no — SHA-256 only |
| `routing_decision` | tier, model, strategy, justification, confidence, config version, `prompt_hash` + `prompt_length` | no |
| `cache_decision` | outcome, similarity, runner-up, threshold, matched entry, conformal status | no |
| Vector cache (`vector_store`) | the **question text and the answer text**, per client, with the embedding | **yes** — similarity search needs it |
| Metrics (Micrometer) | counters and summaries with enum-valued tags only | no |

That fourth row is the one to read twice. The decision trace is hash-only by
design, but the semantic cache is a cache: it stores what it will replay. It is
namespaced per client (`client-namespacing=true` by default) and can be given a
TTL; a deployment that must not retain prompt text turns the cache off, not the
tracing.

### What is replayable

A decision replays **exactly** — it is read back from its row, with the
`routing_config_version`, `embedding_model` and `alpha` it was taken under, so it
is never re-explained by today's rules. Its *analysis* (attribution,
counterfactuals) cannot be replayed from a hash and is recomputed only when a
prompt is supplied again; `provenance` on every explanation says which of the two
the reader is looking at. Nothing here reconstructs a decision after the fact
from log lines: the row is written from the same object the router decided with,
and the metrics from that same object again.

That is what "architectural rather than bolted on" means here. Explainability was
not added as a logging layer over an opaque decision — the decision itself
returns its justification (`ClassificationOutcome`, v2 batch 1), the sealed
hierarchy makes a strategy unable to ship without saying how it decided, and the
recorder is a port the domain does not depend on. Switching recording off
(`gatewai.decisions.enabled=false`) removes the rows, not the justification and
not the metrics.

### The regulatory angle, stated carefully

The EU AI Act — [Regulation (EU) 2024/1689](https://eur-lex.europa.eu/eli/reg/2024/1689/oj)
of 13 June 2024, published in the Official Journal on 12 July 2024 — sets
transparency obligations in **Article 50** for providers and deployers of certain
AI systems: notably, that people interacting directly with an AI system are told
so, and that generative output is machine-readably marked. Per the European
Commission's own FAQ ([Transparency obligations under Article 50 of the AI Act](https://digital-strategy.ec.europa.eu/en/faqs/transparency-obligations-under-article-50-ai-act),
last updated 24 July 2026), those obligations **apply from 2 August 2026**, with
a limited extension to 2 December 2026 for marking systems already on the market.

Two things follow, and neither is a compliance claim.

- **The obligations land on the provider or deployer of the AI system**, not on a
  routing and caching component sitting between them and a model. gatewAI is
  infrastructure; it is not certified, not assessed, and asserting otherwise
  would be exactly the overpromise this documentation avoids elsewhere (see the
  CSRD framing in [`green-accounting.md`](green-accounting.md)).
- **What it does offer is evidence.** An operator answering "which model served
  this request, under which rules, and why" has it per request and per decision,
  versioned, for as long as retention holds — which is the kind of record such an
  obligation is discharged with, produced by the system rather than reconstructed
  around it.

Anything beyond that — an actor-attributed audit trail, immutable retention,
prompt-level retention policy — is not built. [`limitations.md`](../functional/limitations.md)
lists it as absent rather than implied.
