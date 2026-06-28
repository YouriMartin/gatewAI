#!/usr/bin/env bash
#
# Development environment manager for gatewAI.
#
#   scripts/dev.sh start      # infra (Postgres + Ollama) + backend (mvnw spring-boot:run)
#   scripts/dev.sh stop       # stop backend, then stop infra (data volumes kept)
#   scripts/dev.sh restart    # restart the backend only (infra stays up — fast)
#   scripts/dev.sh status     # show infra + backend status and URLs
#   scripts/dev.sh logs       # tail the backend log
#   scripts/dev.sh clean      # stop everything AND wipe the data volumes (asks first)
#
# Notes
# - The infra is the same `compose.yaml` Spring Boot would auto-start; here we own
#   its lifecycle and run the app with Boot's docker-compose support disabled, so
#   `restart` can bounce the app without tearing the infra down.
# - Secrets are read from `.env` (ANTHROPIC_API_KEY, GATEWAI_ADMIN_API_KEY, ...).
# - The Svelte dashboard hot-reload is separate: `npm --prefix src/main/frontend run dev`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f compose.yaml)
DEV_DIR=".dev"
BACK_PID="$DEV_DIR/backend.pid"
BACK_LOG="$DEV_DIR/backend.log"

# Datasource/Ollama coordinates matching compose.yaml (published on localhost).
DB_URL="jdbc:postgresql://localhost:5432/greenaiproxy"
DB_USER="dev"
DB_PASS="dev"
OLLAMA_URL="http://localhost:11434"

# --- pretty logging -------------------------------------------------------
if [ -t 1 ]; then
  C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_DIM=$'\033[2m'; C_0=$'\033[0m'
else
  C_OK=""; C_WARN=""; C_ERR=""; C_DIM=""; C_0=""
fi
info() { printf '%s\n' "${C_DIM}» $*${C_0}"; }
ok()   { printf '%s\n' "${C_OK}✓ $*${C_0}"; }
warn() { printf '%s\n' "${C_WARN}! $*${C_0}"; }
die()  { printf '%s\n' "${C_ERR}✗ $*${C_0}" >&2; exit 1; }

# --- helpers --------------------------------------------------------------
load_env() {
  if [ -f .env ]; then
    set -a; # shellcheck disable=SC1091
    . ./.env; set +a
  fi
}

require_docker() {
  command -v docker >/dev/null 2>&1 || die "docker not found"
  docker info >/dev/null 2>&1 || die "Docker daemon not reachable — start Docker first"
}

is_running() {
  local pidfile="$1" pid
  [ -f "$pidfile" ] || return 1
  pid="$(cat "$pidfile" 2>/dev/null || true)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

port_in_use() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltn "sport = :$port" 2>/dev/null | grep -q LISTEN
  elif command -v lsof >/dev/null 2>&1; then
    lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null \
      && { exec 3>&- 3<&- 2>/dev/null; return 0; } || return 1
  fi
}

# Refuse to start the dev backend when :8080 is already taken — otherwise mvnw
# crashes on bind while you keep hitting whatever else holds the port (typically
# the full-stack container), so your code changes never show up.
ensure_port_free() {
  port_in_use 8080 || return 0
  if docker ps --filter name=gatewai-gateway --filter status=running -q \
      2>/dev/null | grep -q .; then
    warn "Port 8080 is held by the full-stack gateway CONTAINER (docker-compose.yml)."
    warn "Dev mode and the container stack are mutually exclusive — stop the stack:"
    warn "    docker compose -f docker-compose.yml down"
    die  "then run 'scripts/dev.sh start' again."
  fi
  die "Port 8080 is already in use — free it before starting the dev backend."
}

infra_up() {
  info "starting infra (Postgres + Ollama)…"
  "${COMPOSE[@]}" up -d
  local svc cid tries
  for svc in pgvector ollama; do
    cid="$("${COMPOSE[@]}" ps -q "$svc" 2>/dev/null || true)"
    [ -n "$cid" ] || die "no container for service '$svc'"
    tries=0
    until [ "$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null)" = "healthy" ]; do
      tries=$((tries + 1))
      [ "$tries" -gt 60 ] && die "'$svc' did not become healthy in time"
      sleep 2
    done
    ok "$svc healthy"
  done
}

backend_start() {
  if is_running "$BACK_PID"; then
    warn "backend already running (pid $(cat "$BACK_PID"))"; return
  fi
  ensure_port_free
  [ -n "${ANTHROPIC_API_KEY:-}" ] \
    || warn "ANTHROPIC_API_KEY is not set — Claude calls will fail (set it in .env)"
  info "starting backend (mvnw spring-boot:run)…"
  # setsid → new process group, so we can stop mvnw AND its forked JVM together.
  SPRING_DOCKER_COMPOSE_ENABLED=false \
  SPRING_DATASOURCE_URL="$DB_URL" \
  SPRING_DATASOURCE_USERNAME="$DB_USER" \
  SPRING_DATASOURCE_PASSWORD="$DB_PASS" \
  SPRING_AI_OLLAMA_BASE_URL="$OLLAMA_URL" \
  setsid ./mvnw -q -DskipFrontend spring-boot:run >"$BACK_LOG" 2>&1 </dev/null &
  echo $! >"$BACK_PID"
  ok "backend launched (pid $(cat "$BACK_PID")) — logs: $BACK_LOG"
  wait_backend
}

wait_backend() {
  command -v curl >/dev/null 2>&1 || { info "curl absent — skipping readiness probe"; return; }
  info "waiting for http://localhost:8080/actuator/health …"
  local tries=0
  until curl -fsS http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
    tries=$((tries + 1))
    if ! is_running "$BACK_PID"; then
      die "backend exited during startup — see $BACK_LOG"
    fi
    [ "$tries" -gt 90 ] && { warn "not UP yet after ~90s; check $BACK_LOG"; return; }
    sleep 2
  done
  ok "backend is UP"
}

stop_proc() {
  local pidfile="$1" name="$2" pid i
  if ! is_running "$pidfile"; then
    info "$name not running"; rm -f "$pidfile"; return
  fi
  pid="$(cat "$pidfile")"
  info "stopping $name (process group $pid)…"
  kill -TERM "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  for i in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  if kill -0 "$pid" 2>/dev/null; then
    warn "force-killing $name"
    kill -KILL "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
  fi
  rm -f "$pidfile"; ok "$name stopped"
}

print_urls() {
  cat <<EOF
${C_DIM}  Dashboard/API : http://localhost:8080
  Health        : http://localhost:8080/actuator/health
  Admin key     : value of GATEWAI_ADMIN_API_KEY in .env (or grep "Admin API key" $BACK_LOG)
  Dashboard dev : npm --prefix src/main/frontend run dev   (Vite :5173, hot reload)${C_0}
EOF
}

# --- commands -------------------------------------------------------------
cmd_start() {
  require_docker; mkdir -p "$DEV_DIR"; load_env
  ensure_port_free   # bail before touching infra if the container stack is up
  infra_up
  backend_start
  print_urls
}

cmd_stop() {
  load_env
  stop_proc "$BACK_PID" "backend"
  require_docker
  info "stopping infra (data volumes kept)…"
  "${COMPOSE[@]}" stop
  ok "infra stopped"
}

cmd_restart() {
  require_docker; mkdir -p "$DEV_DIR"; load_env
  stop_proc "$BACK_PID" "backend"
  ensure_port_free   # after stopping the dev backend, :8080 must be free
  # Make sure infra is up (it usually still is); cheap if already running.
  infra_up
  backend_start
  print_urls
}

cmd_status() {
  require_docker
  info "infra:"
  "${COMPOSE[@]}" ps || true
  echo
  if is_running "$BACK_PID"; then
    ok "backend running (pid $(cat "$BACK_PID")) — logs: $BACK_LOG"
  else
    warn "backend not running"
  fi
  print_urls
}

cmd_logs() {
  [ -f "$BACK_LOG" ] || die "no backend log yet ($BACK_LOG)"
  info "tailing $BACK_LOG (Ctrl-C to quit)"
  tail -n 120 -f "$BACK_LOG"
}

cmd_clean() {
  load_env
  stop_proc "$BACK_PID" "backend"
  require_docker
  printf '%s' "${C_WARN}This wipes the Postgres + Ollama data volumes. Continue? [y/N] ${C_0}"
  read -r reply
  case "$reply" in
    y|Y|yes|YES)
      "${COMPOSE[@]}" down -v
      ok "infra removed and data volumes wiped"
      ;;
    *) info "aborted — nothing wiped" ;;
  esac
}

usage() {
  sed -n '3,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

case "${1:-}" in
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_restart ;;
  status)  cmd_status ;;
  logs)    cmd_logs ;;
  clean)   cmd_clean ;;
  ""|-h|--help|help) usage ;;
  *) die "unknown command '$1' (try: start|stop|restart|status|logs|clean)" ;;
esac
