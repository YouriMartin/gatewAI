# Evaluation data

Hand-labelled sets the routing and cache decisions are scored against, plus the
baselines a run must stay above. **Full documentation:**
[`docs/technical/evaluation.md`](../../../../docs/technical/evaluation.md).

| File | What it holds |
|---|---|
| `routing-calibration.jsonl` / `routing-test.jsonl` | `(prompt, expectedTier, language, tags)`, 200 / 100, disjoint |
| `cache-calibration.jsonl` / `cache-test.jsonl` | `(query, entry, judgment, language, tags)`, 200 / 100, disjoint |
| `baselines.json` | the floor each metric must stay above, or the build fails |
| `fixtures/` | recorded embeddings and similarities — **generated**, never edited by hand |

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
