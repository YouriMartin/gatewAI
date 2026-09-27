@AGENTS.md

## Claude Code specifics
- Project settings live in `.claude/settings.json` (shared). Personal allowances go in
  `.claude/settings.local.json`, which is not committed.
- Hooks in `.claude/hooks/` enforce two rules from `AGENTS.md`:
  - `require-fresh-tests.sh` blocks `git commit` when `src/`/`pom.xml` changed and
    `target/surefire-reports` is missing, older than the changes, partial, or failing.
    Fix it by running the tests, not by working around the hook.
  - `forbid-jackson2.sh` rejects a Java edit that imports Jackson 2 (`com.fasterxml.jackson.*`
    other than `.annotation`).
- Chat replies to the user are in English.
