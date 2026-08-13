# Occlusion attribution (v2 batch 7)

Which parts of a prompt carried its routing decision. Sources:
`application/service/OcclusionAttributionService`, `domain/model/Occlusion`,
`domain/model/PromptSegmentation`.

## What it explains, exactly

One number: the **cosine similarity between the prompt and the closest example
of the route that won**. That is the number the router decides with when the
strategy is semantic ([`routing.md`](routing.md)), so decomposing it is
decomposing the decision.

The report names the route, the matched utterance and that similarity, precisely
so the segment list cannot be read as an explanation of something else. It
explains a *match*, not a tier ranking and not a model's opinion.

## The method

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

## Segmentation

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

## Cost, and what keeps it in hand

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

## When there is nothing to attribute

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

## Limits

- **Approximate additivity.** Occlusion assumes a segment's contribution is
  roughly independent of the others, which is strictly false for a contextual
  encoder — removing "not" changes what every other word means. It is a useful
  approximation of *which words carried the decision*, not a decomposition of
  it. The `share` column looks like a percentage and invites more trust than it
  deserves; see [`limitations.md`](../functional/limitations.md).
- **It explains the match, not the tier.** Why the winning route beat the others
  is a different question — the counterfactuals of batch 8.
- **Recomputed, never replayed.** No plaintext prompt is stored anywhere, so a
  past decision cannot be re-embedded from its row. Attribution takes the
  prompt; the stored decision says what happened, this says what carried it.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `gatewai.attribution.max-segments` | 20 | hard cap on segments, and so on embedding calls |
| `gatewai.attribution.max-segment-chars` | 200 | length past which a segment is cut into clauses |
| `gatewai.attribution.cache-size` | 500 | bounded LRU of reports |

The use case (`PromptAttributionUseCase`) ships with this batch; the endpoint
that exposes it — `POST /v1/admin/decisions/explain`, admin-only and
rate-limited — arrives with batch 9.
