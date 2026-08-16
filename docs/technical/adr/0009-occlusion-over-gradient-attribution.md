# ADR 0009 — Explain a route match by occlusion, not by gradients

**Status:** Accepted

## Context

The semantic router decides on one number: the cosine similarity between the
request and the closest example of the winning route. "Why did this go to
`CLOUD_PREMIUM`?" is answerable only if that number can be decomposed into the
parts of the prompt that produced it.

The literature's sharper tools — integrated gradients, attention rollout,
gradient × input — all need the **model's internals**: a differentiable graph,
activations, a backward pass. gatewAI reaches its embedding model over HTTP
through Spring AI's `EmbeddingModel` port, and the default deployment is an
Ollama server that returns a vector and nothing else. There is no gradient to
take from the JVM, and there would still be none against a hosted embedding API.

The alternatives actually available:

- **Occlusion** — remove a segment, re-embed, measure what the similarity loses.
  Model-agnostic, needs only the port already in use, costs one embedding call
  per segment plus one.
- **Attention or gradient attribution** — would require embedding the model in
  process (a second runtime, GPU dependencies, a model format lock-in) and would
  bind the explanation to one provider, breaking the local-first,
  provider-agnostic egress ([ADR 0003](0003-ingress-egress-separation.md)).
- **Nothing** — ship the similarity alone and let the operator guess.

## Decision

Occlusion attribution, on demand only, never on the request path.

The prompt is segmented in four passes (lines → sentences → terminators
`BreakIterator` ignores → clauses), each segment carrying **offsets** rather than
text so a repeated sentence is removed in the right place. Contributions are
`sim_full − sim_without(j)`; shares normalize the positive ones; negative
contributions keep their sign, because a segment that pulled the prompt *away*
from the matched route is a finding.

The cost is bounded before it is paid: `max-segments` groups adjacent segments
instead of dropping them, embeddings run in parallel on virtual threads, and the
result is memoized in a bounded LRU keyed by (prompt hash, embedding model,
routing config version) — the same key that stales a calibration, for the same
reason.

## Consequences

- **The explanation works against any embedding provider**, including one behind
  an API that will never expose internals. It also survives swapping the model:
  only the numbers move.
- **Additivity is approximate.** Contributions do not sum to the similarity;
  removing two segments is not the sum of removing each. The report presents
  shares of positive contribution, and
  [`limitations.md`](../../functional/limitations.md) says this in as many words —
  the output reads more precise than it is, and that is the risk to manage.
- **Correlated segments split the credit**, and a duplicated idea can show two
  small contributions where one large one exists. Occlusion measures
  *removability*, not importance.
- **It explains a match, not a tier.** Decomposing the winning similarity says
  nothing about the gap to the runner-up tier — which is why counterfactuals
  (v2 batch 8) exist as a separate answer to a separate question.
- **Cost is linear in segments**, so it is an admin tool, rate-limited on
  `POST /v1/admin/decisions/explain`, and never something the routing path waits
  on.
- **Segmentation is a judgement call, and it shows.** `BreakIterator` breaks
  after abbreviations; that imprecision is documented rather than papered over
  with a regex that would fail differently.
