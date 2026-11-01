#!/usr/bin/env bash
# Replays a small synthetic set against a running gateway (v4 C.4), so the
# dashboard and the decision trace have something to show.
#
#   scripts/demo-traffic.sh                    # http://localhost:8080, the quickstart key
#   GATEWAI_URL=http://localhost:18080 GATEWAI_API_KEY=gw_… scripts/demo-traffic.sh
#
# The set is scripts/demo-traffic.jsonl — 30 requests, every prompt invented for
# this file, no real conversation in it. One entry per line:
#   chat          one user prompt; repeats and paraphrases of earlier entries are
#                 there on purpose, so the cache has something to hit;
#   conversation  a system prompt and user turns, sent one request per turn with
#                 the answers the gateway actually gave in between (conversation-
#                 sticky routing, ADR 0015; the last turn's history is longer than
#                 the cache accepts, so it shows a BYPASS);
#   tool          a prompt with tools, so it takes the pass-through path (ADR 0016).
#                 When the answer is a tool call, the scripted `tool_result` is sent
#                 back as the tool message — a second request.
# Every request uses the routed model `auto`. Answers are whatever the egress
# returns: the mock preset's echo, or a real model's text.
#
# Prints one line per request (cache outcome, model) and exits non-zero if any
# request failed. Needs curl and jq.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
URL="${GATEWAI_URL:-http://localhost:8080}"
KEY="${GATEWAI_API_KEY:-gw_quickstart-demo-key}"
DATASET="${1:-$REPO_ROOT/scripts/demo-traffic.jsonl}"

command -v jq > /dev/null || { echo "demo-traffic.sh needs jq"; exit 1; }
[ -f "$DATASET" ] || { echo "No data set at $DATASET"; exit 1; }

sent=0
failures=0
declare -A outcomes=()
HEADERS="$(mktemp)"
BODY="$(mktemp)"
trap 'rm -f "$HEADERS" "$BODY"' EXIT

# POSTs one chat completion body and logs one line; the answer is left in $BODY
# (not printed, so the counters survive: no subshell). Non-zero on a non-2xx.
post() { # $1 = label, $2 = body
  local status cache model
  curl -sS -o "$BODY" -D "$HEADERS" "$URL/v1/chat/completions" \
    -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
    --data-binary "$2" || : > "$BODY"
  status="$(awk 'NR == 1 {print $2}' "$HEADERS" | tr -d '\r')"
  cache="$(header x-gatewai-cache)"
  model="$(header x-gatewai-model)"
  sent=$((sent + 1))
  printf '%3d  %-30s %-4s %-7s %s\n' "$sent" "$1" "${status:----}" "${cache:--}" "${model:--}"
  if [[ "$status" != 2* ]]; then
    failures=$((failures + 1))
    printf '     %s\n' "$(cat "$BODY")"
    return 1
  fi
  outcomes[${cache:-none}]=$((${outcomes[${cache:-none}]:-0} + 1))
}

header() { grep -i "^$1:" "$HEADERS" | tail -1 | awk '{print $2}' | tr -d '\r'; }

chat() { # $1 = entry
  local id body
  id="$(jq -r '.id' <<< "$1")"
  body="$(jq -c '{model: "auto", messages: [{role: "user", content: .prompt}]}' <<< "$1")"
  post "$id" "$body"
}

conversation() { # $1 = entry
  local id messages turn i=0
  id="$(jq -r '.id' <<< "$1")"
  messages="$(jq -c 'if .system then [{role: "system", content: .system}] else [] end' <<< "$1")"
  while IFS= read -r turn; do
    i=$((i + 1))
    messages="$(jq -c --arg t "$turn" '. + [{role: "user", content: $t}]' <<< "$messages")"
    post "$id #$i" "$(jq -c '{model: "auto", messages: .}' <<< "$messages")" || return
    messages="$(jq -c --slurpfile a "$BODY" \
      '. + [{role: "assistant", content: ($a[0].choices[0].message.content // "")}]' <<< "$messages")"
  done < <(jq -r '.turns[]' <<< "$1")
}

tool() { # $1 = entry
  local id request call
  id="$(jq -r '.id' <<< "$1")"
  request="$(jq -c '{model: "auto", tools: .tools, messages: [{role: "user", content: .prompt}]}' <<< "$1")"
  post "$id #1" "$request" || return
  call="$(jq -c '.choices[0].message.tool_calls[0] // empty' "$BODY")"
  # A real model may answer without calling the tool; then there is no second turn.
  [ -n "$call" ] || return 0
  request="$(jq -c --argjson call "$call" --argjson e "$1" \
    '.messages += [{role: "assistant", content: null, tool_calls: [$call]},
                   {role: "tool", tool_call_id: $call.id, content: $e.tool_result}]' <<< "$request")"
  post "$id #2" "$request"
}

printf 'Replaying %s against %s\n\n' "${DATASET#"$REPO_ROOT"/}" "$URL"
printf '%3s  %-30s %-4s %-7s %s\n' '#' 'entry' 'http' 'cache' 'model'
while IFS= read -r entry; do
  [ -n "$entry" ] || continue
  case "$(jq -r '.kind' <<< "$entry")" in
    chat) chat "$entry" ;;
    conversation) conversation "$entry" ;;
    tool) tool "$entry" ;;
    *) echo "Unknown kind in: $entry"; failures=$((failures + 1)) ;;
  esac
done < "$DATASET"

summary=""
for outcome in "${!outcomes[@]}"; do summary+="${summary:+, }${outcomes[$outcome]} $outcome"; done
printf '\n%d request(s) sent: %s\n' "$sent" "${summary:-none answered}"
if [ "$failures" -gt 0 ]; then
  printf '%d request(s) failed\n' "$failures"
  exit 1
fi
