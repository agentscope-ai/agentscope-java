#!/usr/bin/env bash
#
# dev-up.sh - start the AgentScope Service stack locally.
#
#   Gateway    :8080
#   aistiod    :8081  (Go control plane: /api/*, /api/v1/*, console SPA)
#   Data       :8082
#   Scheduler  :8083
#   Postgres   :5432  (schemas cp + rt + dp; via Docker)
#
# aistiod runs standalone here (AISTIO_ENABLE_KUBERNETES=false), so no
# reconcilers, CRD-backed APIs, or ASDP gRPC listener are started.
#
# Usage:
#   scripts/dev-up.sh
#   BUILDER_REBUILD=1 scripts/dev-up.sh   # full monorepo mvn install + aistiod rebuild
#   scripts/dev-down.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${BUILDER_RUN_DIR:-$ROOT/.dev-stack}"
LOG_DIR="$RUN_DIR/logs"
PID_DIR="$RUN_DIR/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

GATEWAY_PORT="${BUILDER_GATEWAY_PORT:-8080}"
CONTROL_PORT="${BUILDER_CONTROL_PORT:-8081}"
DATA_PORT="${BUILDER_DATA_PORT:-8082}"
SCHED_PORT="${BUILDER_SCHEDULER_PORT:-8083}"
PG_PORT="${BUILDER_PG_PORT:-5432}"
PG_CONTAINER="${BUILDER_PG_CONTAINER:-agentscope-dev-pg}"

# jdbc profile requires >=32 chars and rejects known short defaults (see InternalTokenStartupValidator)
export BUILDER_INTERNAL_TOKEN="${BUILDER_INTERNAL_TOKEN:-local-dev-internal-token-at-least-32chars}"
export BUILDER_JWT_SECRET="${BUILDER_JWT_SECRET:-builder-default-dev-secret-change-in-production-32chars}"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-jdbc}"

DB_URL="jdbc:postgresql://localhost:${PG_PORT}/builder?currentSchema=dp"
AISTIO_DSN="postgres://builder:builder@localhost:${PG_PORT}/builder?sslmode=disable"
AISTIO_RUNTIME_DSN="${AISTIO_DSN}&search_path=rt"

jar_of() {
    find "$ROOT/$1/target" -maxdepth 1 -name "$1-*.jar" \
        ! -name "*sources*" ! -name "*javadoc*" | head -1
}

wait_health() {
    local name="$1" port="$2" timeout="${3:-90}" path="${4:-/actuator/health}" i
    for i in $(seq 1 "$timeout"); do
        if curl -sf -m 2 "http://localhost:${port}${path}" >/dev/null 2>&1; then
            echo "  OK ${name} healthy on :${port}"
            return 0
        fi
        sleep 1
    done
    echo "  FAIL ${name} did not become healthy within ${timeout}s - see ${LOG_DIR}/${name}.log" >&2
    return 1
}

start() {
    local name="$1" pidfile="$2"; shift 2
    mkdir -p "$LOG_DIR" "$PID_DIR"
    if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        echo "  * ${name} already running (pid $(cat "$pidfile"))"
        return 0
    fi
    # Detach into a new session so planes survive after this script (and Cursor/CI
    # wrappers) exit. macOS has no setsid(1); python3 is available on the supported
    # local toolchain.
    python3 - "$LOG_DIR/${name}.log" "$@" <<'PY' &
import os, sys
log_path = sys.argv[1]
argv = sys.argv[2:]
os.makedirs(os.path.dirname(log_path) or ".", exist_ok=True)
os.setsid()
log = open(log_path, "wb")
os.dup2(log.fileno(), 1)
os.dup2(log.fileno(), 2)
devnull = open(os.devnull, "rb")
os.dup2(devnull.fileno(), 0)
os.execvpe(argv[0], argv, os.environ)
PY
    echo $! >"$pidfile"
    echo "  * ${name} started (pid $!)"
}

# ---------------------------------------------------------------- build Java
# Always install from the monorepo root (not agentscope-service/ alone):
# fat jars embed ~/.m2 harness/core/extensions; a service-only build can keep a
# stale snapshot. Root `mvn install` also walks agentscope-service children
# (unlike `-pl agentscope-service`, which only builds the packaging=pom aggregator).
if [ "${BUILDER_REBUILD:-0}" = "1" ] || [ ! -f "$(jar_of service-gateway || true)" ]; then
    echo "==> Building agentscope-java monorepo (mvn install -DskipTests)"
    MONOREPO_ROOT="$(cd "$ROOT/.." && pwd)"
    (cd "$MONOREPO_ROOT" && mvn install -DskipTests -q)
fi

# ---------------------------------------------------------------- build aistiod
AISTIO_BIN="$ROOT/aistio/bin/aistiod"
if [ "${BUILDER_REBUILD:-0}" = "1" ] || [ ! -x "$AISTIO_BIN" ]; then
    echo "==> Building aistiod"
    (cd "$ROOT/aistio" && mkdir -p bin && go build -o bin/aistiod ./cmd/aistiod)
fi

# ---------------------------------------------------------------- postgres
if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run the shared Postgres instance" >&2
    exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
    if docker ps -a --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
        echo "==> Starting existing Postgres container ${PG_CONTAINER}"
        docker start "$PG_CONTAINER" >/dev/null
    else
        echo "==> Creating Postgres container ${PG_CONTAINER} on :${PG_PORT}"
        docker run -d --name "$PG_CONTAINER" \
            -e POSTGRES_DB=builder \
            -e POSTGRES_USER=builder \
            -e POSTGRES_PASSWORD=builder \
            -p "${PG_PORT}:5432" \
            -v "$ROOT/docker/postgres-init.sql:/docker-entrypoint-initdb.d/01-schemas.sql:ro" \
            postgres:17 >/dev/null
    fi
fi

echo "==> Waiting for Postgres"
for i in $(seq 1 60); do
    if docker exec "$PG_CONTAINER" pg_isready -U builder -d builder >/dev/null 2>&1; then
        echo "  OK Postgres ready"
        break
    fi
    sleep 1
    if [ "$i" = "60" ]; then
        echo "Postgres did not become ready" >&2
        exit 1
    fi
done

# Ensure schemas exist even if volume was created before init script
docker exec "$PG_CONTAINER" psql -U builder -d builder -c \
    "CREATE SCHEMA IF NOT EXISTS cp; CREATE SCHEMA IF NOT EXISTS rt; CREATE SCHEMA IF NOT EXISTS dp;" >/dev/null

# ---------------------------------------------------------------- planes
mkdir -p "$LOG_DIR" "$PID_DIR"

# Free ports held by orphaned planes from earlier runs (missing/stale pidfiles).
free_port() {
    local port="$1" pids
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    [ -n "$pids" ] || return 0
    echo "  * freeing :${port} (pid ${pids})"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    sleep 1
    # shellcheck disable=SC2086
    kill -9 $pids 2>/dev/null || true
}
free_port "$GATEWAY_PORT"
free_port "$CONTROL_PORT"
free_port "$DATA_PORT"
free_port "$SCHED_PORT"

echo "==> Starting planes (Postgres: ${DB_URL})"

start control "$PID_DIR/control.pid" \
    env AISTIO_ENABLE_KUBERNETES=false \
        AISTIO_PRODUCT_DSN="$AISTIO_DSN" \
        AISTIO_HTTP_BIND=":${CONTROL_PORT}" \
        BUILDER_JWT_SECRET="$BUILDER_JWT_SECRET" \
        BUILDER_INTERNAL_TOKEN="$BUILDER_INTERNAL_TOKEN" \
        BUILDER_DATA_URL="http://localhost:${DATA_PORT}" \
        AISTIO_WORKSPACE_ROOT="$RUN_DIR/workspaces" \
        AISTIO_STATIC_DIR="$ROOT/aistio/ui" \
    "$AISTIO_BIN" \
        --storage-driver=postgres \
        --storage-dsn="$AISTIO_RUNTIME_DSN" \
        --log-format=console

start data "$PID_DIR/data.pid" \
    env BUILDER_DB_URL="$DB_URL" BUILDER_DB_DRIVER=org.postgresql.Driver \
        BUILDER_DB_USER=builder BUILDER_DB_PASSWORD=builder \
        BUILDER_DATA_PORT="$DATA_PORT" \
        BUILDER_CONTROL_URL="http://localhost:${CONTROL_PORT}" \
        BUILDER_INTERNAL_TOKEN="$BUILDER_INTERNAL_TOKEN" \
        BUILDER_JWT_SECRET="$BUILDER_JWT_SECRET" \
    java -jar "$(jar_of service-dataplane)"

start scheduler "$PID_DIR/scheduler.pid" \
    env BUILDER_DB_URL="$DB_URL" BUILDER_DB_DRIVER=org.postgresql.Driver \
        BUILDER_DB_USER=builder BUILDER_DB_PASSWORD=builder \
        BUILDER_SCHEDULER_PORT="$SCHED_PORT" \
        BUILDER_CONTROL_URL="http://localhost:${CONTROL_PORT}" \
        BUILDER_DATA_URL="http://localhost:${DATA_PORT}" \
        BUILDER_INTERNAL_TOKEN="$BUILDER_INTERNAL_TOKEN" \
        BUILDER_JWT_SECRET="$BUILDER_JWT_SECRET" \
    java -jar "$(jar_of service-scheduler)"

start gateway "$PID_DIR/gateway.pid" \
    env BUILDER_GATEWAY_PORT="$GATEWAY_PORT" \
        BUILDER_CONTROL_URL="http://localhost:${CONTROL_PORT}" \
        BUILDER_DATA_URL="http://localhost:${DATA_PORT}" \
        BUILDER_SCHEDULER_URL="http://localhost:${SCHED_PORT}" \
    java -jar "$(jar_of service-gateway)"

echo "==> Waiting for health"
wait_health control "$CONTROL_PORT" 60 /healthz
wait_health data "$DATA_PORT"
wait_health scheduler "$SCHED_PORT"
wait_health gateway "$GATEWAY_PORT" 30

cat <<EOF

==> AgentScope Service stack is up (aistiod + Java DP)

  Console (SPA via gateway):  http://localhost:${GATEWAY_PORT}/
  Default login:              admin / admin

  Frontend HMR (optional):    cd frontend && npm run dev

  Logs:   ${LOG_DIR}
  Stop:   scripts/dev-down.sh
EOF
