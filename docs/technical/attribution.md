# Explaining a routing decision (v2 batches 7–8)

Two questions about the same ranking, answered on demand:

- **attribution** (batch 7) — which parts of the prompt carried the *match*;
- **counterfactuals** (batch 8) — where the request would have gone *instead*,
  and by how little it missed.

Sources: `application/service/OcclusionAttributionService`,
`application/service/RouteCounterfactualService`, `domain/model/Occlusion`,
`domain/model/PromptSegmentation`, `domain/model/Counterfactuals`.

## Occlusion attribution (batch 7)

### What it explains, exactly

One number: the **cosine similarity between the prompt and the closest example
of the route that won**. That is the number the router decides with when the
strategy is semantic ([`routing.md`](routing.md)), so decomposing it is
decomposing the decision.

The report names the route, the matched utterance and that similarity, precisely
so the segment list cannot be read as an explanation of something else. It
explains a *match*, not a tier ranking and not a model's opinion.

### The method

```
sim_full        = similarity(embed(prompt), utterance)
sim_without(j)  = similarity(embed(prompt minus segment j), utterance)
contribution(j) = sim_full − sim_without(j)
share(j)        = max(contribution(j), 0) / Σ max(contribution(k), 0)
```

Removing a segment and seeing what the similarity loses. Everything after the
embedding calls is arithmetic on numbers (`Occlusion`), separated from the
embedding so it can be tested exactly rather than against whatever a model
happened to output.

**Negative contributions are kept.** A segment whose removal *raises* the
similarity was pulling the prompt away from the matched route; that is a
finding, not noise. It takes no share of the reason (shares are normalized over
positive contributions only), but it keeps its sign in the report.

Gradient-based attribution would be sharper and is not available: the gateway
reaches the embedding model through an HTTP port, with no access to its
internals from the JVM. Occlusion needs nothing but the port already in use.

### Segmentation

Four passes, each refining the ranges of the last (`PromptSegmentation`):

1. **line breaks** — a bullet list is a list of ideas;
2. **sentences** — the JDK's `BreakIterator`;
3. **terminators the sentence pass ignored** — `BreakIterator` only breaks
   before a capital, so `"refactor this service. add tests."` is *one* sentence
   to it, and lowercase prompts are most of what a gateway receives;
4. **clauses** — a segment longer than `max-segment-chars` is cut at `,;:—–()`,
   because a 400-character block attributed as one piece says no more than "the
   prompt did it".

Segments carry **offsets**, not just text: occlusion removes segment *j*, and a
substring search would remove the wrong copy whenever a sentence repeats.

Above `max-segments`, adjacent segments are **grouped**, never dropped — every
character stays inside exactly one segment, so the attributions describe the
whole prompt rather than a sample of it.

**Known imprecision**: `BreakIterator` breaks after abbreviations, so "Ask Dr.
Martin about the schema." becomes two segments. Left standing rather than
patched with per-language abbreviation lists: one extra boundary costs one extra
segment, grouping absorbs it, and an attribution over slightly wrong boundaries
is still an attribution.

### Cost, and what keeps it in hand

**n + 1 embedding calls** per uncached report, against the same local Ollama the
gateway serves requests with. This is the one place v2 can visibly load the box.
Four things bound it:

- **On demand only.** Nothing computes an attribution while routing. The router
  decides; someone later asks why.
- **The cap.** `gatewai.attribution.max-segments` (20) is a hard bound on n.
- **The cache.** Reports are cached per
  `(prompt hash, embedding model, routing config version)` in a **bounded LRU**
  (`gatewai.attribution.cache-size`, 500). Bounded matters: prompts are user
  input, and an unbounded map keyed by prompt is a memory leak with a
  plausible-sounding name. It is deliberately **not** the per-request embedding
  memo from batch 0.2 — that one lives for one request and is scoped to it.
- **Virtual threads.** The occluded embeddings run concurrently on a
  virtual-thread executor, so the wall clock is roughly one embedding call, not
  n. Not structured concurrency: that is still a preview feature and excluded
  from this project's core.

The routing config version is in the cache key and the plan did not ask for it
(D29). It has to be: an attribution decomposes the similarity to *the matched
route's closest example*, so editing that route — or its examples — changes what
the numbers are even about. Keyed on the prompt alone, a cached report would go
on explaining a decision the gateway no longer takes.

### When there is nothing to attribute

`AttributionStatus` says so rather than returning an empty list:

| Status | Meaning |
|---|---|
| `COMPUTED` | segments were scored |
| `NOT_APPLICABLE_STRATEGY` | the configured strategy (`heuristic`, `llm`) does not decide by similarity, so there is no similarity to attribute |
| `NO_ROUTES_CONFIGURED` | the strategy uses routes, but none is configured |
| `EMPTY_PROMPT` | nothing to segment |

A genuine embedding failure is **not** in that enum and propagates. This runs on
demand, off the request path: an admin asking why is owed an error, not a
plausible-looking report built on a failed call.

### Limits

- **Approximate additivity.** Occlusion assumes a segment's contribution is
  roughly independent of the others, which is strictly false for a contextual
  encoder — removing "not" changes what every other word means. It is a useful
  approximation of *which words carried the decision*, not a decomposition of
  it. The `share` column looks like a percentage and invites more trust than it
  deserves; see [`limitations.md`](../functional/limitations.md).
- **It explains the match, not the tier.** Why the winning route beat the others
  is a different question — the counterfactuals below.
- **Recomputed, never replayed.** No plaintext prompt is stored anywhere, so a
  past decision cannot be re-embedded from its row. Attribution takes the
  prompt; the stored decision says what happened, this says what carried it.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `gatewai.attribution.max-segments` | 20 | hard cap on segments, and so on embedding calls |
| `gatewai.attribution.max-segment-chars` | 200 | length past which a segment is cut into clauses |
| `gatewai.attribution.cache-size` | 500 | bounded LRU of reports |

The use case (`PromptAttributionUseCase`) ships with this batch; the endpoint
that exposes it — `POST /v1/admin/decisions/explain`, admin-only and
rate-limited — arrives with batch 9.

---

## Counterfactuals (batch 8)

Where the request would have gone instead. Semantic routing already ranks
**every** route against the request and then uses only the top of that list
(`RouteScoring`, [`routing.md`](routing.md)); the rest of it is the
counterfactual, and reading it costs nothing beyond having ranked.

Rendered as *"this request would have gone to `CLOUD_PREMIUM` had it looked more
like «Refactor the architecture of this Java service», which it missed by
0.04"*.

### Why the gap is the number to read

A tier in a decision log looks like a fact about the request. A gap of 0.01 says
it is not: reword the prompt slightly and the same router sends it elsewhere. A
gap of 0.30 says the opposite, and both are invisible in the chosen tier alone.
It is the same signal the cascade escalates on (`top1 − top2`,
[`routing.md`](routing.md)) — here shown per alternative, and to a human rather
than to a gate.

### What is kept, and what is dropped

For each of the nearest non-chosen routes (`max-alternatives`, default 3):
its tier, its closest example, that similarity, and
`gap = chosen similarity − this similarity`. Two filters, both deliberate
(`Counterfactuals`):

- **Routes leading back to the chosen tier are dropped.** They are not
  counterfactual: "it would have gone to `LOCAL`" about a request that went to
  `LOCAL` describes no alternative at all. The plan said "top non-chosen
  routes", which on a default configuration where several routes share a tier
  spends the whole list on non-answers (**D32**).
- **One route per tier**, the best-scoring one, since that is the route that
  would have won that tier. Three ways to reach the same tier crowd out the
  tiers the reader has not been told about yet (**D33**).

### Cost

**One embedding call** — the prompt — against a route index that is already
built. Cheap enough that, unlike attribution, **nothing is cached**: a cache key
would have to be kept in step with the routing config for a saving of one call,
so counterfactuals recompute and are always current.

That index is now shared (`SemanticRouteIndex`) by both explanation services,
because batch 9's explain endpoint answers both questions about one prompt and
two private indexes would embed every configured example twice. The classifier
keeps its own on purpose: it sits on the request path, reads a different
configuration source, and its latency must not be coupled to an admin tool.

### Only configuration is ever quoted

The returned utterances come from route **configuration** and nothing else — the
index holds no user data, and a test asserts it. The structure would happily
hold prompts, and an explanation that quoted one client's request back to
another would be a data leak wearing the costume of a feature.

### When there is nothing to compare

| Status | Meaning |
|---|---|
| `COMPUTED` | alternatives were ranked |
| `NOT_APPLICABLE_STRATEGY` | `heuristic` and `llm` rank nothing, so nothing came second |
| `NO_ROUTES_CONFIGURED` | the strategy uses routes, but none is configured |
| `EMPTY_PROMPT` | nothing to compare |
| `NO_ALTERNATIVE_TIER` | routes exist and one won, but every other route leads to the tier that won anyway — no wording would have changed the outcome. The chosen route is still reported |

### Limits

- **It explains the ranking, not the final tier.** Whether the winner cleared
  the similarity threshold, was overridden by a fallback, escalated in the
  cascade or was pinned by the client is the decision row's business; batch 9's
  endpoint joins the two. Counterfactuals answer "which route came closest",
  which is only the whole answer when the router took the routes' word for it.
- **Recomputed, never replayed** — same reason as attribution: no plaintext
  prompt is persisted.
- **A gap is not a probability.** It is a cosine difference on the current
  routes; it says how close the ranking was, not how likely the other outcome
  was.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `gatewai.counterfactuals.max-alternatives` | 3 | how many alternative outcomes to keep, closest first |

The use case (`RouteCounterfactualUseCase`) ships with this batch; like
attribution, it becomes reachable over HTTP in batch 9.
