# Evaluation data

Hand-labelled sets the routing and cache decisions are scored against, plus the
baselines a run must stay above. **Full documentation:**
[`docs/technical/evaluation.md`](../../../../docs/technical/evaluation.md).

| File | What it holds |
|---|---|
| `routing-test.jsonl` | `(prompt, expectedTier, language, tags)`, 100, disjoint from the calibration half |
| `cache-test.jsonl` | `(query, entry, judgment, language, tags)`, 100, disjoint from the calibration half |
| `baselines.json` | the floor each metric must stay above, or the build fails |
| `fixtures/` | recorded embeddings and similarities — **generated**, never edited by hand |

The **calibration** halves live in `src/main/resources/eval/` instead: the
gateway ships them so it can calibrate itself (v2 batch 3). The test halves stay
here, out of the jar — a calibration fitted on its own test set measures nothing.

Editing any `.jsonl` invalidates the fixtures; the harness then fails with the
command to re-record:

```bash
docker compose up -d ollama
./mvnw -Pit test -Dtest=EvalFixtureRecorderTest -Deval.record=true
```

Two labelling rules that are choices rather than facts, repeated here because
they bite when adding cases: a cross-lingual pair is `NO` (the cached answer
would come back in the wrong language), and a volatile question is `NO` even when
the two texts are identical (no threshold can make a stale answer fresh).
