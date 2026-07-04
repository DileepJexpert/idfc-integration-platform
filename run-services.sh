#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-services.sh — start ALL IDFC microservices on the HOST (fast dev loop).
#
# WHY this is fast: it does NOT build Docker images or run containers. It builds
# the boot jars ONCE (./gradlew bootJar) and launches each service as a plain
# `java -jar` process with the `local` Spring profile, wired to the dockerized
# infra over host ports (Kafka :29092, Aerospike :3000, vendor mocks :9101-05).
#
# YOU run the infra yourself, once (it is long-lived):
#     docker compose -f docker-compose.infra.yml up -d
#
# Then, from IntelliJ (or any terminal):
#     ./run-services.sh            # build jars + start every service in background
#     ./run-services.sh --no-build # skip the build, just (re)start from existing jars
#     ./run-services.sh --registry # use the journey-registry seam (needs a published
#                                  # journey) instead of the classpath fallback
#     ./run-services.sh status     # what's up, on which port/PID
#     ./run-services.sh logs kyc   # tail one service's log
#     ./run-services.sh stop       # stop everything this script started
#     ./run-services.sh restart    # stop + start
#
# Logs:  .run/logs/<service>.log     PIDs:  .run/pids/<service>.pid
# Requires: JDK 21 on PATH (the jars are built for 21) and the infra up.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="${VERSION:-0.1.0-SNAPSHOT}"
RUN_DIR="$ROOT/.run"
LOG_DIR="$RUN_DIR/logs"
PID_DIR="$RUN_DIR/pids"
JAVA_OPTS="${JAVA_OPTS:-}"

# Optional machine-local port overrides (gitignored). Put lines like
#   PORT_CUSTOMER_PARTY=8095      # when something else holds a default port
# in .run/ports.env and they win over the defaults below.
[ -f "$RUN_DIR/ports.env" ] && . "$RUN_DIR/ports.env"

# effective port for a service: PORT_<NAME_UPPER_WITH_UNDERSCORES> env wins, else default
eff_port() { local var="PORT_$(printf '%s' "$1" | tr 'a-z-' 'A-Z_')"; printf '%s' "${!var:-$2}"; }

# service | gradle module dir | http port   (registry first so it precedes the engine)
SERVICES=(
  "journey-registry|platform/journey-registry|8104"
  "origination-journey|orchestration/origination-journey|8082"
  "sfdc-ingress-edge|edges/sfdc-ingress-edge|8080"
  "digital-partner-edge|edges/digital-partner-edge|8081"
  "customer-party|capabilities/customer-party|8090"
  "kyc|capabilities/kyc|8091"
  "bureau|capabilities/bureau|8092"
  "scoring|capabilities/scoring|8093"
  "lending-origination|capabilities/lending-origination|8094"
  "device-financing|capabilities/device-financing|8110"
  "fusion-hcm|capabilities/fusion-hcm|8111"
  "file-batch-edge|edges/file-batch-edge|8112"
)

c_green=$'\e[32m'; c_red=$'\e[31m'; c_yellow=$'\e[33m'; c_dim=$'\e[2m'; c_off=$'\e[0m'
info()  { printf '%s\n' "$*"; }
warn()  { printf '%s%s%s\n' "$c_yellow" "$*" "$c_off"; }
err()   { printf '%s%s%s\n' "$c_red" "$*" "$c_off" >&2; }

# --- lightweight TCP probe (bash builtin; works in Git Bash / WSL) -----------
port_open() { # host port -> 0 if a listener answers
  (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null && { exec 3>&- 3<&-; return 0; } || return 1
}

check_infra() {
  # Infra (Kafka/Aerospike/mocks) is YOUR responsibility — you start it manually.
  # We only probe and WARN; we never block the one-click launch. Services retry
  # their broker/db connections on their own, so a late infra start still works.
  local ok=1
  port_open localhost 29092 || { warn "  Kafka   localhost:29092  NOT reachable"; ok=0; }
  port_open localhost 3000  || { warn "  Aerospike localhost:3000 NOT reachable"; ok=0; }
  if [ "$ok" -ne 1 ]; then
    warn "Infra not fully reachable. Start it yourself when ready:"
    warn "    docker compose -f docker-compose.infra.yml up -d"
    warn "Continuing anyway — services will retry their connections."
  else
    info "${c_green}Infra reachable${c_off} (Kafka :29092, Aerospike :3000)"
  fi
}

pid_alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

# Registry seam readiness: the engine in registry mode FAILS CLOSED at startup
# unless the registry has a published journey (EngineConfiguration.journeyRegistry).
# The local registry self-seeds the canonical journeys, but that completes a beat
# after its port opens — so hold the engine until /published-journeys is non-empty.
wait_for_registry_seeded() {
  local port token url body tries=0 max=60
  port="$(eff_port journey-registry 8104)"
  token="${REGISTRY_AUTH_TOKEN:-dev-registry-token}"
  url="http://localhost:$port/api/v1/published-journeys"
  if ! command -v curl >/dev/null 2>&1; then
    warn "  curl not found — cannot confirm the registry seeded; pausing 20s before the engine"; sleep 20; return 0
  fi
  printf '  waiting for the registry to publish its seed journeys'
  while [ "$tries" -lt "$max" ]; do
    body="$(curl -fsS -H "X-Registry-Token: $token" "$url" 2>/dev/null || true)"
    case "$body" in
      \[*journeyKey*) printf ' %sready%s\n' "$c_green" "$c_off"; return 0 ;;  # non-empty published set
    esac
    printf '.'; tries=$((tries + 1)); sleep 1
  done
  printf '\n'; warn "  registry has no published journey after ${max}s — the engine may fail closed (./run-services.sh logs journey-registry)"
  return 0
}

start_all() {
  local no_build=0 journey_source="classpath"
  for a in "$@"; do
    case "$a" in
      --no-build) no_build=1 ;;
      --registry) journey_source="registry" ;;
    esac
  done

  check_infra
  mkdir -p "$LOG_DIR" "$PID_DIR"

  if [ "$no_build" -eq 0 ]; then
    info "Building boot jars (./gradlew bootJar)…"
    ( cd "$ROOT" && ./gradlew bootJar --console=plain -q )
  fi

  if [ "$journey_source" = "classpath" ]; then
    info "Engine journey-source=${c_yellow}classpath${c_off} (bundled journeys; use --registry for the designer→engine seam)"
  else
    info "Engine journey-source=${c_green}registry${c_off} — the registry self-seeds the canonical journeys on the"
    info "  local profile; the engine is held below until they are published so it never fails closed."
  fi

  for entry in "${SERVICES[@]}"; do
    IFS='|' read -r name module port <<<"$entry"
    port="$(eff_port "$name" "$port")"
    local jar="$ROOT/$module/build/libs/$name-$VERSION.jar"
    local pidfile="$PID_DIR/$name.pid"

    if [ -f "$pidfile" ] && pid_alive "$(cat "$pidfile")"; then
      warn "  $name already running (pid $(cat "$pidfile")) — skipping"
      continue
    fi
    if [ ! -f "$jar" ]; then
      err "  $name: jar not found ($jar). Run without --no-build."
      continue
    fi

    # Registry seam: hold the engine until the registry has published its seed
    # journeys, so registry mode never races the fail-closed bootstrap.
    if [ "$name" = "origination-journey" ] && [ "$journey_source" = "registry" ]; then
      wait_for_registry_seeded
    fi

    # SERVER_PORT applies the (possibly overridden) port; the engine also reads
    # IDFC_ENGINE_JOURNEY_SOURCE.
    local envprefix="SERVER_PORT=$port"
    [ "$name" = "origination-journey" ] && envprefix="$envprefix IDFC_ENGINE_JOURNEY_SOURCE=$journey_source"

    ( cd "$ROOT" && env $envprefix nohup java $JAVA_OPTS -jar "$jar" \
        --spring.profiles.active=local \
        >"$LOG_DIR/$name.log" 2>&1 & echo $! >"$pidfile" )
    printf '  %sstarted%s %-22s port %s  (pid %s)\n' "$c_green" "$c_off" "$name" "$port" "$(cat "$pidfile")"
  done

  info ""
  info "All launched. Boot takes ~5-10s each; watch a log with:  ./run-services.sh logs <name>"
  info "Edges:  SFDC  http://localhost:8080   ·  Digital http://localhost:8081"
  info "Engine: http://localhost:8082    ·  Registry http://localhost:8104"
}

stop_all() {
  [ -d "$PID_DIR" ] || { info "Nothing to stop."; return; }
  for entry in "${SERVICES[@]}"; do
    IFS='|' read -r name _ _ <<<"$entry"
    local pidfile="$PID_DIR/$name.pid"
    [ -f "$pidfile" ] || continue
    local pid; pid="$(cat "$pidfile")"
    if pid_alive "$pid"; then
      kill "$pid" 2>/dev/null || true
      printf '  %sstopped%s %s (pid %s)\n' "$c_red" "$c_off" "$name" "$pid"
    fi
    rm -f "$pidfile"
  done
}

status_all() {
  printf '%-22s %-6s %-8s %s\n' "SERVICE" "PORT" "PID" "STATE"
  for entry in "${SERVICES[@]}"; do
    IFS='|' read -r name module port <<<"$entry"
    port="$(eff_port "$name" "$port")"
    local pidfile="$PID_DIR/$name.pid" pid="-" state="${c_dim}stopped${c_off}"
    if [ -f "$pidfile" ]; then
      pid="$(cat "$pidfile")"
      if pid_alive "$pid"; then
        if port_open localhost "$port"; then state="${c_green}up${c_off}"; else state="${c_yellow}booting${c_off}"; fi
      else pid="-"; fi
    fi
    printf '%-22s %-6s %-8s %b\n' "$name" "$port" "$pid" "$state"
  done
}

logs_one() {
  local name="${1:-}"; local f="$LOG_DIR/$name.log"
  [ -n "$name" ] || { err "usage: ./run-services.sh logs <service>"; exit 1; }
  [ -f "$f" ] || { err "no log for '$name' at $f"; exit 1; }
  tail -f "$f"
}

case "${1:-start}" in
  start|"")   shift || true; start_all "$@" ;;
  --no-build|--registry) start_all "$@" ;;
  stop)       stop_all ;;
  restart)    shift || true; stop_all; sleep 1; start_all "$@" ;;
  status)     status_all ;;
  logs)       logs_one "${2:-}" ;;
  *)          err "unknown command: $1"; info "commands: start | stop | restart | status | logs <name>"; exit 1 ;;
esac