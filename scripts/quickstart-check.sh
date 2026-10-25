#!/usr/bin/env bash
# Runs the README's quickstart as written and asserts what it promises (v4 C.3).
#
#   GATEWAI_IMAGE=gatewai:ci scripts/quickstart-check.sh
#
# The commands come from the README itself — the first ```bash block after the
# `quickstart-check` marker — so the README cannot change without this check
# running the new text, and cannot rot while the check stays green:
#   1. the download line is checked for the file it names, and that file is
#      copied from this checkout instead of fetched (the branch under test, not
#      whatever `main` holds on GitHub);
#   2. every other line runs verbatim, in a scratch directory, with
#      GATEWAI_IMAGE pointing at the image under test;
#   3. the last command (the chat completion) runs twice: the first answer must
#      carry `X-GatewAI-Cache: MISS`, the second `HIT`.
# It also prints the time from `up` to the first successful answer. Everything
# it starts is removed on exit.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
README="$REPO_ROOT/README.md"
export GATEWAI_IMAGE="${GATEWAI_IMAGE:?set GATEWAI_IMAGE to the image under test}"
# The checked commands must not inherit a preset from the caller's shell.
unset GATEWAI_PRESET GATEWAI_ADMIN_API_KEY GATEWAI_MODEL_SMALL GATEWAI_MODEL_MEDIUM GATEWAI_MODEL_LARGE

failures=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# The block, one logical command per line (backslash continuations joined).
mapfile -t commands < <(awk '
  /quickstart-check/ { armed = 1; next }
  armed && /^```bash/ { inside = 1; next }
  inside && /^```/ { exit }
  inside {
    line = $0
    if (sub(/\\[[:space:]]*$/, "", line)) { buf = buf line; next }
    print buf line; buf = ""
  }' "$README")

if [ "${#commands[@]}" -lt 3 ]; then
  echo "No quickstart block found in README.md (expected a \`\`\`bash block after the quickstart-check marker)"
  exit 1
fi

WORK="$(mktemp -d)"
compose_file=""
cleanup() {
  if [ "$failures" -gt 0 ] && [ -n "$compose_file" ]; then
    printf '\n--- gateway log (last 200 lines) ---\n'
    (cd "$WORK" && docker compose -f "$compose_file" logs --tail 200 gateway 2>&1)
  fi
  [ -n "$compose_file" ] && (cd "$WORK" && docker compose -f "$compose_file" down -v > /dev/null 2>&1)
  rm -rf "$WORK"
}
trap cleanup EXIT

printf '\n\033[1m%s\033[0m\n' "README quickstart on $GATEWAI_IMAGE"

# 1. The download line names a file this repository has.
download="${commands[0]}"
compose_file="$(printf '%s' "$download" \
  | sed -n 's#^curl .*https://raw.githubusercontent.com/YouriMartin/gatewAI/main/\([^ ]*\)$#\1#p')"
if [ -n "$compose_file" ] && [ -f "$REPO_ROOT/$compose_file" ]; then
  cp "$REPO_ROOT/$compose_file" "$WORK/"
  pass "downloads $compose_file, which exists"
else
  fail "first command is not a download of a file in this repository: $download"
  exit 1
fi

# 2. Everything between the download and the request, verbatim.
last=$((${#commands[@]} - 1))
start=$SECONDS
for ((i = 1; i < last; i++)); do
  if (cd "$WORK" && bash -c "${commands[$i]}"); then
    pass "ran: ${commands[$i]}"
  else
    fail "failed: ${commands[$i]}"
    exit 1
  fi
done

# 3. The same request twice: MISS, then HIT.
request() { (cd "$WORK" && bash -c "${commands[$last]}"); }
cache_header() { printf '%s' "$1" | grep -i '^x-gatewai-cache:' | tr -d '\r' | awk '{print $2}'; }

first="$(request)"
elapsed=$((SECONDS - start))
status="$(printf '%s' "$first" | head -1 | awk '{print $2}')"
if [ "$status" = "200" ] && [ "$(cache_header "$first")" = "MISS" ]; then
  pass "first answer: 200, X-GatewAI-Cache: MISS (${elapsed} s after up)"
else
  fail "first answer: status '${status:-none}', X-GatewAI-Cache '$(cache_header "$first")', expected 200 MISS"
  printf '%s\n' "$first"
fi

second="$(request)"
if [ "$(cache_header "$second")" = "HIT" ]; then
  pass "second answer: X-GatewAI-Cache: HIT"
else
  fail "second answer: X-GatewAI-Cache '$(cache_header "$second")', expected HIT"
  printf '%s\n' "$second"
fi

if [ "$failures" -gt 0 ]; then
  printf '\n%d check(s) failed\n' "$failures"
  exit 1
fi
printf '\nAll checks passed\n'
