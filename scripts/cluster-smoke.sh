#!/usr/bin/env bash
# Cluster scenario for v3 lot B.5 — the proof, in one run.
#
#   docker compose -f docker-compose.cluster.yml up --build -d
#   ./scripts/cluster-smoke.sh
#
# Five checks, one per mechanism the lot introduced. Each prints PASS or FAIL and
# the script exits non-zero if any failed, so it can be read by a person or by
# CI. It talks to the balancer (:8080) for anything a client would do, and to a
# specific node (:8081 / :8082) only where the question is "what does *that* node
# believe".
set -uo pipefail

COMPOSE="docker compose -f docker-compose.cluster.yml"
LB="${LB_URL:-http://localhost:8080}"
N1="${NODE1_URL:-http://localhost:8081}"
N2="${NODE2_URL:-http://localhost:8082}"
# Ask the node which key it was actually given, rather than assuming: compose
# reads .env too, so a repository with one configured would otherwise 401 every
# request in this script and blame the cluster for it.
KEY="${GATEWAI_ADMIN_API_KEY:-$(docker compose -f docker-compose.cluster.yml exec -T gateway-1 printenv GATEWAI_ADMIN_API_KEY 2>/dev/null | tr -d '\r\n')}"
KEY="${KEY:-gw_cluster-demo-admin-key}"
AUTH=(-H "Authorization: Bearer ${KEY}")
JSON=(-H 'Content-Type: application/json')

failures=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }
warn() { printf '  \033[33mNOTE\033[0m %s\n' "$1"; }
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# `grep -q` exits on the first match, which kills curl with SIGPIPE — and under
# `pipefail` that failure, not grep's success, is the pipeline's status. Count
# instead: grep -c reads to the end, so nothing gets a broken pipe.
contains() { # $1 = url, $2 = needle
  [ "$(curl -s "$1" | grep -c -- "$2" || true)" -gt 0 ]
}

psql_q() {
  $COMPOSE exec -T pgvector psql -U "${POSTGRES_USER:-dev}" \
    -d "${POSTGRES_DB:-greenaiproxy}" -t -A -c "$1" 2>/dev/null | tr -d '\r'
}

chat() { # $1 = base url, $2 = prompt -> prints the HTTP status
  curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" "${JSON[@]}" \
    -d "{\"model\":\"gatewai-auto\",\"messages\":[{\"role\":\"user\",\"content\":\"$2\"}]}" \
    "$1/v1/chat/completions"
}

# ---------------------------------------------------------------------------
section "0. Both nodes are up and answer through the balancer"
# ---------------------------------------------------------------------------
for url in "$N1" "$N2" "$LB"; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url/actuator/health")
  [ "$code" = "200" ] && pass "$url is UP" || fail "$url returned $code"
done

# ---------------------------------------------------------------------------
section "1. Routing config propagates between nodes (B.1)"
# ---------------------------------------------------------------------------
# Edit on node 1 only; node 2 must converge within its sync interval.
marker=$(( (RANDOM % 400) + 100 ))
curl -s -o /dev/null -X PUT "${AUTH[@]}" "${JSON[@]}" -d "{
  \"strategy\":\"embedding\",\"entry_length_threshold\":${marker},
  \"premium_length_threshold\":900,\"premium_keywords\":[\"cluster-probe\"],
  \"route_similarity_threshold\":0.25,\"cascade_margin_band\":0.02,
  \"routes\":[{\"name\":\"probe\",\"tier\":\"cloud_entry\",\"examples\":[\"an example\"]}]}" \
  "$N1/v1/admin/routing"

started=$(date +%s)
converged=""
for _ in $(seq 1 120); do
  if [ "$(curl -s "${AUTH[@]}" "$N2/v1/admin/routing" \
      | grep -c "\"entry_length_threshold\":${marker}" || true)" -gt 0 ]; then
    converged=$(( $(date +%s) - started ))
    break
  fi
  sleep 0.5
done
if [ -n "$converged" ]; then
  pass "node 2 picked up node 1's edit in ~${converged}s"
else
  fail "node 2 never picked up the edit"
fi

version_rows=$(psql_q "SELECT count(DISTINCT revision) FROM routing_config;")
[ "$version_rows" = "1" ] && pass "one stored configuration, not one per node" \
  || fail "routing_config holds $version_rows revisions"

# ---------------------------------------------------------------------------
section "2. Deferred jobs cross nodes and run exactly once (B.2)"
# ---------------------------------------------------------------------------
# Start from a clean queue *and* a clean bucket, so the script is idempotent: the
# counts below are about this run, and the async submit is rate-limited like any
# other completion.
psql_q "DELETE FROM deferred_job;" > /dev/null
psql_q "DELETE FROM rate_limit_bucket;" > /dev/null
jobs=12
for i in $(seq 1 $jobs); do
  curl -s -o /dev/null -X POST "${AUTH[@]}" "${JSON[@]}" \
    -d "{\"model\":\"gatewai-auto\",\"messages\":[{\"role\":\"user\",\"content\":\"cluster job $i: describe item $i\"}]}" \
    "$LB/v1/chat/completions/async"
done
for _ in $(seq 1 60); do
  [ "$(psql_q "SELECT count(*) FROM deferred_job WHERE status <> 'COMPLETED';")" = "0" ] && break
  sleep 1
done
done_count=$(psql_q "SELECT count(*) FROM deferred_job WHERE status = 'COMPLETED';")
[ "$done_count" = "$jobs" ] && pass "$done_count/$jobs jobs completed" \
  || fail "only $done_count/$jobs jobs completed"

# Which node ran what is emergent, not guaranteed: both poll the same queue and
# whoever ticks first takes the next job. Reported, not asserted — the property
# that must hold is the one below it.
claimers=$(psql_q "SELECT count(DISTINCT claimed_by) FROM deferred_job;")
if [ "${claimers:-0}" -ge 2 ]; then
  pass "work spread across $claimers nodes"
else
  warn "one node claimed everything this run — legal, the tick phase decided"
fi
psql_q "SELECT '    ' || claimed_by || ' ran ' || count(*) || ' job(s)'
        FROM deferred_job GROUP BY claimed_by ORDER BY claimed_by;"

dupes=$(psql_q "SELECT count(*) FROM (SELECT r.correlation_id FROM request_log r
  JOIN deferred_job j ON j.id::text = r.correlation_id
  GROUP BY 1 HAVING count(*) <> 1) x;")
[ "${dupes:-1}" = "0" ] && pass "every job executed exactly once" \
  || fail "$dupes job(s) executed more than once"

# ---------------------------------------------------------------------------
section "3. The rate limit is the cluster's, not each node's (B.3)"
# ---------------------------------------------------------------------------
# Must match the compose default, or this check measures the wrong number.
limit="${GATEWAI_RATELIMIT_REQUESTS_PER_MINUTE:-60}"
psql_q "DELETE FROM rate_limit_bucket;" > /dev/null
allowed=0; limited=0
started=$(date +%s)
for i in $(seq 1 $(( limit + 10 ))); do
  code=$(chat "$LB" "rate probe $i via the balancer")
  [ "$code" = "200" ] && allowed=$((allowed + 1)) || limited=$((limited + 1))
done
elapsed=$(( $(date +%s) - started ))
# The bucket refills greedily while the burst is in flight — limit/60 tokens a
# second — so the ceiling is the quota plus what came back during the run. The
# claim being tested is "one quota, not one per node": an unshared limiter would
# have allowed about twice the limit.
budget=$(( limit + (elapsed * limit / 60) + 1 ))
if [ "$allowed" -le "$budget" ] && [ "$limited" -gt 0 ]; then
  pass "$allowed allowed / $limited refused (quota $limit, +$((budget - limit)) refilled over ${elapsed}s; per-node would allow ~$((limit * 2)))"
else
  fail "$allowed allowed / $limited refused — expected at most $budget for a shared quota of $limit"
fi

# ---------------------------------------------------------------------------
section "4. A gated job runs on one node only (B.4)"
# ---------------------------------------------------------------------------
# Counting purge log lines proves nothing: the second node would find nothing to
# delete either way. So the lock is held from outside — if the gate is real, both
# nodes then skip every tick. -189118924 is "gatewai".hashCode(), 1 is
# LeaderTask.DECISION_PURGE.
before1=$($COMPOSE logs gateway-1 2>/dev/null | grep -c 'skipping')
before2=$($COMPOSE logs gateway-2 2>/dev/null | grep -c 'skipping')
psql_q "SELECT pg_advisory_lock(-189118924, 1); SELECT pg_sleep(14);" > /dev/null
after1=$($COMPOSE logs gateway-1 2>/dev/null | grep -c 'skipping')
after2=$($COMPOSE logs gateway-2 2>/dev/null | grep -c 'skipping')
if [ "$after1" -gt "$before1" ] && [ "$after2" -gt "$before2" ]; then
  pass "both nodes skipped the purge while the lock was held ($((after1-before1)) + $((after2-before2)) skips)"
else
  fail "the purge gate did not hold (skips: $((after1-before1)) + $((after2-before2)))"
fi

admins=$(psql_q "SELECT count(*) FROM api_client WHERE admin;")
[ "$admins" = "1" ] && pass "two nodes booted, exactly one admin client seeded" \
  || fail "$admins admin clients seeded"

# ---------------------------------------------------------------------------
section "5. Metrics tell the nodes apart (B.5)"
# ---------------------------------------------------------------------------
for node in "$N1:gateway-1" "$N2:gateway-2"; do
  url="${node%:*}"; name="${node##*:}"
  if contains "$url/actuator/prometheus" "instance=\"${name}\""; then
    pass "$name tags its series with instance=\"$name\""
  else
    fail "$name does not carry its instance tag"
  fi
done

printf '\n'
if [ "$failures" -eq 0 ]; then
  printf '\033[32mAll cluster checks passed.\033[0m\n'
else
  printf '\033[31m%s check(s) failed.\033[0m\n' "$failures"
fi
exit $(( failures > 0 ? 1 : 0 ))
