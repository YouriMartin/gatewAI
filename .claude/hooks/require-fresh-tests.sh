#!/usr/bin/env bash
# PreToolUse(Bash): block `git commit` when Java/Maven sources changed but the test
# reports do not prove a full, green `./mvnw test` ran after those changes.
# Exit 2 = block; stderr is shown to the agent.
set -euo pipefail

cmd=$(jq -r '.tool_input.command // ""')
grep -qE '(^|[;&|[:space:]])git[[:space:]]+commit([[:space:]]|$)' <<<"$cmd" || exit 0

cd "${CLAUDE_PROJECT_DIR:-.}"

relevant='^(src/(main|test)/(java|resources)/|pom\.xml$|checkstyle\.xml$|spotbugs-exclude\.xml$)'

changed=$(git diff --cached --name-only --diff-filter=ACMR)
# `git add … && git commit` or `git commit -a`: the files are not staged yet when
# this hook runs, so look at the working tree too.
if grep -qE '(^|[;&|[:space:]])git[[:space:]]+add([[:space:]]|$)|(^|[[:space:]])-[a-zA-Z]*a[a-zA-Z]*([[:space:]]|$)|--all' <<<"$cmd"; then
  changed+=$'\n'$(git diff --name-only --diff-filter=ACMR)
  changed+=$'\n'$(git ls-files --others --exclude-standard)
fi

newest_src=0
while IFS= read -r f; do
  [[ -n $f && -f $f ]] || continue
  grep -qE "$relevant" <<<"$f" || continue
  m=$(stat -c %Y "$f")
  (( m > newest_src )) && newest_src=$m
done <<<"$changed"
(( newest_src == 0 )) && exit 0 # docs/frontend-only commit: nothing to prove

block() {
  echo "Commit blocked: $1" >&2
  echo "Run \`./mvnw -DskipFrontend test\` (full suite, no -Dtest) and commit once it is green." >&2
  exit 2
}

reports=target/surefire-reports
shopt -s nullglob
files=("$reports"/TEST-*.xml)
(( ${#files[@]} > 0 )) || block "no test reports in $reports."

for r in "${files[@]}"; do
  # Ignore reports left behind by test classes that no longer exist.
  cls=$(basename "$r" .xml); cls=${cls#TEST-}; cls=${cls%%\$*}
  [[ -f src/test/java/${cls//.//}.java ]] || continue
  (( $(stat -c %Y "$r") >= newest_src )) \
    || block "$(basename "$r") is older than your changes (tests not re-run, or only a -Dtest subset)."
  grep -qE '(failures|errors)="[1-9]' "$r" && block "$(basename "$r") reports failures or errors."
done
exit 0
