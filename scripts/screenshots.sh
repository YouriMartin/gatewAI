#!/usr/bin/env bash
# Regenerates the README screenshots in one command (v4 C.4).
#
#   GATEWAI_IMAGE=gatewai:local scripts/screenshots.sh
#
#   1. starts docker-compose.quickstart.yml as its own Compose project
#      (gatewai-screenshots) on 127.0.0.1:18080, with a fresh volume, so the
#      pictures show this run's traffic and nothing else;
#   2. replays the bundled synthetic set (scripts/demo-traffic.sh);
#   3. captures docs/assets/dashboard.png and docs/assets/decision-explanation.png
#      with Playwright (a devDependency of the dashboard; Chromium's headless
#      shell is downloaded on the first run);
#   4. rewrites the README caption between the `screenshots` markers: where the
#      traffic came from, and the date — each image carries the same line;
#   5. removes the stack and its volume.
#
# GATEWAI_IMAGE defaults to the published image, like the quickstart. Before the
# first release, build one: `docker build -t gatewai:local .`.
#
# The egress is the quickstart's mock preset unless you choose another one, with
# the same variables as docker-compose.quickstart.yml (GATEWAI_PRESET, the three
# GATEWAI_MODEL_*, OPENAI_COMPATIBLE_*…). Real traffic also needs
# GATEWAI_DEMO_PROVIDER: the provider's name as the caption should print it.
#
# Needs docker (Compose v2), curl, jq, and Node >= 24 — on the PATH, or the one
# `./mvnw package` downloads into target/node.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FRONTEND="$REPO_ROOT/src/main/frontend"
ASSETS="$REPO_ROOT/docs/assets"
README="$REPO_ROOT/README.md"
# One prompt of the replayed set: two clauses, so the attribution has two segments.
PROMPT="Review this login handler for SQL injection and other security vulnerabilities. Keep it short, please."
export GATEWAI_PORT="${GATEWAI_PORT:-18080}"
URL="http://127.0.0.1:$GATEWAI_PORT"
COMPOSE=(docker compose -p gatewai-screenshots -f "$REPO_ROOT/docker-compose.quickstart.yml")

PRESET="${GATEWAI_PRESET:-mock}"
if [ "$PRESET" != mock ] && [ -z "${GATEWAI_DEMO_PROVIDER:-}" ]; then
  echo "GATEWAI_PRESET=$PRESET sends real traffic: set GATEWAI_DEMO_PROVIDER to the"
  echo "provider's name, as the caption should print it (e.g. \"OpenRouter\")."
  exit 1
fi

command -v jq > /dev/null || { echo "screenshots.sh needs jq"; exit 1; }
if ! command -v npm > /dev/null; then
  if [ -x "$REPO_ROOT/target/node/npm" ]; then
    export PATH="$REPO_ROOT/target/node:$PATH"
  else
    echo "No Node on the PATH and none in target/node (run ./mvnw package once, or install Node >= 24)"
    exit 1
  fi
fi

ok=0
cleanup() {
  if [ "$ok" -eq 0 ]; then
    printf '\n--- gateway log (last 100 lines) ---\n'
    "${COMPOSE[@]}" logs --tail 100 gateway 2>&1
  fi
  "${COMPOSE[@]}" down -v > /dev/null 2>&1
}
trap cleanup EXIT

step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

step "Starting a fresh stack (preset $PRESET) on $URL"
"${COMPOSE[@]}" down -v > /dev/null 2>&1
"${COMPOSE[@]}" up -d --wait || exit 1
# Ask the gateway which key it was given: compose reads .env too.
KEY="$("${COMPOSE[@]}" exec -T gateway printenv GATEWAI_ADMIN_API_KEY | tr -d '\r\n')"

step "Replaying the demo traffic"
GATEWAI_URL="$URL" GATEWAI_API_KEY="$KEY" "$REPO_ROOT/scripts/demo-traffic.sh" || exit 1

DATE="$(date -u +%F)"
if [ "$PRESET" = mock ]; then
  SOURCE="Mock egress: the built-in echo answered, no model was called"
else
  MODELS="$(curl -fsS "$URL/v1/models" -H "Authorization: Bearer $KEY" \
    | jq -r '[.data[].id | select(. != "auto")] | join(", ")')"
  SOURCE="Real traffic through $GATEWAI_DEMO_PROVIDER (models: $MODELS)"
fi
STAMP="$SOURCE · synthetic demo traffic (scripts/demo-traffic.jsonl) · captured $DATE"

step "Capturing the dashboard"
[ -d "$FRONTEND/node_modules/playwright" ] || npm --prefix "$FRONTEND" ci || exit 1
# The headless shell is all a headless launch uses: 120 MiB, not the full browser.
npm --prefix "$FRONTEND" exec -- playwright install --only-shell chromium || exit 1
GATEWAI_URL="$URL" GATEWAI_API_KEY="$KEY" SCREENSHOT_SOURCE="$STAMP" \
  SCREENSHOT_PROMPT="$PROMPT" SCREENSHOT_OUT="$ASSETS" \
  npm --prefix "$FRONTEND" run --silent screenshots || exit 1

step "Writing the README caption"
CAPTION="$(cat <<EOF
![gatewAI dashboard: the figures after the demo traffic](docs/assets/dashboard.png)

![The "why this decision" panel: one prompt from the demo set explained](docs/assets/decision-explanation.png)

*$SOURCE. Synthetic demo traffic ([\`scripts/demo-traffic.jsonl\`](scripts/demo-traffic.jsonl),
30 requests), captured $DATE by \`scripts/screenshots.sh\` — rerun it to check
every figure.*
EOF
)"
CAPTION="$CAPTION" awk '
  /<!-- screenshots:/ { print; print ENVIRON["CAPTION"]; skip = 1; found++; next }
  /<!-- \/screenshots -->/ { skip = 0 }
  !skip { print }
  END { if (found != 1) exit 1 }' "$README" > "$README.tmp" \
  && mv "$README.tmp" "$README" \
  || { rm -f "$README.tmp"; echo "README.md has no screenshots markers"; exit 1; }

ok=1
printf '\nWrote docs/assets/dashboard.png, docs/assets/decision-explanation.png and the README caption (%s)\n' "$SOURCE"
