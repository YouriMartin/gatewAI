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
wrong in the worse way must not be the one nobody can inspect.

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
  claims no compliance of its own.
- **Not the carbon record.** The explanation *references* it by correlation id;
  the figures live in `request_log` and in the green report, and duplicating
  them is how two sources of truth start disagreeing.
