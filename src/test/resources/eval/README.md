# Evaluation data

Hand-labelled sets the routing and cache decisions are scored against, plus the
baselines a run must stay above. **Full documentation:**
[`docs/technical/evaluation.md`](../../../../docs/technical/evaluation.md).

| File | What it holds |
|---|---|
| `routing-test.jsonl` | `(prompt, expectedTier, language, tags)`, 100, disjoint from the calibration half |
| `cache-test.jsonl` | `(query, entry, judgment, language, tags)`, 100, disjoint from the calibration half |
| `conversation-test.jsonl` | `(stored, incoming, judgment, language, tags)`: two OpenAI `messages` arrays, ~100, test only (v4 A.1) |
| `baselines.json` | the floor each metric must stay above, or the build fails |
| `fixtures/` | recorded embeddings and similarities — **generated**, never edited by hand |

The **calibration** halves live in `src/main/resources/eval/` instead: the
gateway ships them so it can calibrate itself (v2 batch 3). The test halves stay
here, out of the jar — a calibration fitted on its own test set measures nothing.

Editing any `.jsonl` invalidates the fixtures; the harness then fails with the
command to re-record:

```bash
./mvnw test -Dtest=EvalFixtureRecorderTest -Deval.record=true
```

Since v3 lot A the recorder needs no infrastructure: the embedding model runs
in-process from the jar's own resources.

Two labelling rules that are choices rather than facts, repeated here because
they bite when adding cases: a cross-lingual pair is `NO` (the cached answer
would come back in the wrong language), and a volatile question is `NO` even when
the two texts are identical (no threshold can make a stale answer fresh).

## Conversation cases (v4 A.1)

Each case asks one question: `stored`'s answer is in the cache — may it be served
to `incoming`? `YES` only if it would be a correct answer to `incoming`, in
`incoming`'s context. There is no calibration half: these cases measure the
blind spot, they do not fit anything. One tag per case, and each tag has a fixed
judgment:

| Tag | What differs between `stored` and `incoming` | Judgment |
|---|---|---|
| `follow-up-collision` | the history; the last user turn is identical and only makes sense with that history ("continue", "give me an example in Java", "why?") | `NO` |
| `system-prompt-collision` | the system prompt (persona, audience, output language, tenant); the user turn is identical | `NO` |
| `end-user-collision` | the per-user data inside one system-prompt template (order, account, customer name); the user turn is identical | `NO` |
| `template-prefix` | the end of a single-turn prompt longer than the embedding window (128 tokens); the first 128 tokens are identical | `NO` |
| `same-context-paraphrase` | only the wording of the last user turn; system prompt and history identical | `YES` |
| `first-turn-paraphrase` | only the wording of the first (and only) user turn; system prompt identical | `YES` |

The choices behind these rules:

- **A different system prompt is always `NO`**, even when the difference looks
  immaterial to the question. The gateway cannot tell an immaterial instruction
  from a material one, and a label that required that judgment would score the
  cache on something it has no way to see.
- **`follow-up-collision` last turns must depend on the history.** An identical,
  self-contained last turn after unrelated histories is not in this tag.
- **`template-prefix` cases are checked against the real tokenizer**: the harness
  fails if either prompt fits the window or if they differ before token 128, so
  the tag cannot silently stop meaning what it says.
- The two existing rules still apply: a cross-lingual pair is `NO`, and so is a
  volatile question.
