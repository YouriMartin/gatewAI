#!/usr/bin/env bash
# Fails when a workflow uses an action by tag or branch instead of a commit SHA
# (v4 C.1). A tag can be moved to other code after review; a 40-hex commit SHA
# cannot. Every `uses:` must read
#
#   uses: owner/repo[/path]@<40-hex sha> # <version>
#
# the trailing comment being what a reader (and Dependabot, which rewrites both)
# goes by. Local actions (`./…`) and `docker://` images pinned by sha256 digest
# are exempt. Run with a directory to check another tree:
#
#   scripts/check-pinned-actions.sh [dir]     # default: .github
set -euo pipefail

DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)/.github}"
pinned='^[^@[:space:]]+@[0-9a-f]{40}[[:space:]]+#[[:space:]]*[^[:space:]]'
exempt='^(\./|docker://[^[:space:]]+@sha256:[0-9a-f]{64})'

bad=0
checked=0
while IFS= read -r hit; do
  file="${hit%%:*}"
  rest="${hit#*:}"
  line="${rest%%:*}"
  ref=$(printf '%s' "${rest#*:}" | sed -E 's/^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*//; s/["'\'']//g')
  checked=$((checked + 1))
  if [[ "$ref" =~ $exempt ]] || [[ "$ref" =~ $pinned ]]; then
    continue
  fi
  printf '%s:%s: not pinned to a commit SHA with a version comment: %s\n' "$file" "$line" "$ref"
  bad=$((bad + 1))
done < <(grep -rnE '^[[:space:]]*(-[[:space:]]*)?uses:' "$DIR" --include='*.yml' --include='*.yaml' || true)

if [ "$bad" -gt 0 ]; then
  printf '%d of %d action reference(s) are not SHA-pinned\n' "$bad" "$checked"
  exit 1
fi
printf 'All %d action reference(s) are pinned to a commit SHA\n' "$checked"
