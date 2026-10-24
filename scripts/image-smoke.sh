#!/usr/bin/env bash
# Smoke test of one gateway image on one architecture (v4 C.1).
#
#   scripts/image-smoke.sh <image-ref> [platform]
#   scripts/image-smoke.sh ghcr.io/yourimartin/gatewai@sha256:… linux/arm64
#   EXPECTED_VERSION=0.1.0 scripts/image-smoke.sh gatewai:latest
#
# Starts a throwaway pgvector and the image with the `mock` egress on a private
# network, then checks what a stranger pulling the image would hit first: the
# container is the architecture asked for, it boots (embedding model and its
# native libraries loaded — they are per-architecture), /actuator/info reports
# the expected version, and one chat completion is answered. Prints PASS/FAIL
# per check, the gateway log on failure, and exits non-zero if any failed.
# Everything it starts is removed on exit.
set -uo pipefail

IMAGE="${1:?usage: $0 <image-ref> [platform]}"
PLATFORM="${2:-}"
PORT="${SMOKE_PORT:-18080}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-600}"
KEY='gw_image-smoke-admin-key'
NAME="gatewai-smoke-$$"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

failures=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }

cleanup() {
  docker rm -f "$NAME-gw" "$NAME-db" > /dev/null 2>&1
  docker network rm "$NAME" > /dev/null 2>&1
}
trap cleanup EXIT

docker network create "$NAME" > /dev/null || exit 1

# pgvector runs on the host's own architecture: only the gateway is under test.
docker run -d --name "$NAME-db" --network "$NAME" --network-alias pgvector \
  -e POSTGRES_DB=gatewai -e POSTGRES_USER=dev -e POSTGRES_PASSWORD=dev \
  -v "$REPO_ROOT/docker/postgres/init.sql:/docker-entrypoint-initdb.d/init.sql:ro,z" \
  pgvector/pgvector:pg17 > /dev/null || exit 1
for _ in $(seq 1 60); do
  docker exec "$NAME-db" pg_isready -U dev -d gatewai > /dev/null 2>&1 && break
  sleep 1
done

platform_args=()
[ -n "$PLATFORM" ] && platform_args=(--platform "$PLATFORM")
docker run -d --name "$NAME-gw" --network "$NAME" "${platform_args[@]}" \
  -e SPRING_PROFILES_ACTIVE=mock \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://pgvector:5432/gatewai \
  -e SPRING_DATASOURCE_USERNAME=dev -e SPRING_DATASOURCE_PASSWORD=dev \
  -e GATEWAI_ADMIN_API_KEY="$KEY" \
  -p "127.0.0.1:$PORT:8080" "$IMAGE" > /dev/null || exit 1
BASE="http://127.0.0.1:$PORT"

printf '\n\033[1m%s\033[0m\n' "Image $IMAGE${PLATFORM:+ ($PLATFORM)}"

# 1. The architecture the container actually runs — a manifest list that
# resolved to the wrong entry would otherwise pass every check below.
if [ -n "$PLATFORM" ]; then
  want="${PLATFORM#linux/}"
  case "$want" in amd64) want=x86_64 ;; arm64*) want=aarch64 ;; esac
  got=$(docker exec "$NAME-gw" uname -m 2>/dev/null | tr -d '\r')
  [ "$got" = "$want" ] && pass "runs as $got" || fail "runs as '$got', expected $want"
fi

# 2. Boot. The health endpoint is UP only once the context is up, which loads
# the ONNX model and the tokenizer through their native libraries. The HTTP
# status is the signal (200 only when the aggregate is UP, 503 otherwise): the
# body lists every component's own status, so grepping it for UP proves nothing.
start=$SECONDS
up=false
while [ $((SECONDS - start)) -lt "$BOOT_TIMEOUT" ]; do
  if [ "$(docker inspect -f '{{.State.Running}}' "$NAME-gw" 2>/dev/null)" != "true" ]; then
    break
  fi
  if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/actuator/health")" = "200" ]; then
    up=true
    break
  fi
  sleep 3
done
if $up; then
  pass "healthy after $((SECONDS - start)) s"
else
  fail "not healthy after $((SECONDS - start)) s"
fi

if $up; then
  # 3. The version the build stamped, read where a bug report would read it.
  info=$(curl -s "$BASE/actuator/info")
  version=$(printf '%s' "$info" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
  if [ -n "${EXPECTED_VERSION:-}" ]; then
    [ "$version" = "$EXPECTED_VERSION" ] && pass "reports version $version" \
      || fail "reports version '$version', expected $EXPECTED_VERSION"
  else
    [ -n "$version" ] && pass "reports version $version" || fail "no build.version in /actuator/info"
  fi

  # 4. One request through the whole chain (cache miss → router → mock egress).
  response=$(curl -s -D - --max-time 120 -X POST \
    -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
    -d '{"model":"gatewai-auto","messages":[{"role":"user","content":"image smoke test"}]}' \
    "$BASE/v1/chat/completions")
  status=$(printf '%s' "$response" | head -1 | awk '{print $2}')
  cache=$(printf '%s' "$response" | grep -i '^x-gatewai-cache:' | tr -d '\r' | awk '{print $2}')
  if [ "$status" = "200" ] && [ "$(printf '%s' "$response" | grep -c '"choices"' || true)" -gt 0 ]; then
    pass "answered a chat completion (X-GatewAI-Cache: ${cache:-absent})"
  else
    fail "chat completion returned '${status:-no response}'"
  fi
fi

if [ "$failures" -gt 0 ]; then
  printf '\n--- gateway log (last 200 lines) ---\n'
  docker logs --tail 200 "$NAME-gw" 2>&1
  printf '\n%d check(s) failed\n' "$failures"
  exit 1
fi
printf '\nAll checks passed\n'
