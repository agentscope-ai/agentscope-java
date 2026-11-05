#!/usr/bin/env bash
#
# dev-up.sh - start the AgentScope Service stack locally.
#
#   Gateway    :18080
#   service-controlplane    :8081  (Go control plane: /api/*, /api/v1/*, console SPA)
#   Data       :8082
#   Portal     :8084
#   Hands      :8085 (platform HTTP/MQ service)
#   Postgres   :5432  (schemas cp + rt + portal + dp + ma; via Docker)
#
# service-controlplane runs standalone here (CONTROL_PLANE_ENABLE_KUBERNETES=false), so no
# reconcilers, CRD-backed APIs, or ASDP gRPC listener are started.
#
# Usage:
#   scripts/dev-up.sh
#   BUILDER_REBUILD=1 scripts/dev-up.sh   # rebuild binaries and preserve data
#   BUILDER_REBUILD=1 BUILDER_RESET_DB=1 scripts/dev-up.sh  # explicitly reset disposable dev schemas
#   scripts/dev-down.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${BUILDER_RUN_DIR:-$ROOT/.dev-stack}"
LOG_DIR="$RUN_DIR/logs"
PID_DIR="$RUN_DIR/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

STARTUP_SUCCEEDED=0
STARTED_PLANES=0
cleanup_failed_startup() {
    local status=$?
    if [ "$status" -ne 0 ] && [ "$STARTED_PLANES" = "1" ] && [ "$STARTUP_SUCCEEDED" != "1" ] && [ "${BUILDER_KEEP_FAILED_STACK:-0}" != "1" ]; then
        echo "==> Startup failed; stopping partially started planes" >&2
        "$ROOT/scripts/dev-down.sh" || true
    fi
}
trap cleanup_failed_startup EXIT

GATEWAY_PORT="${BUILDER_GATEWAY_PORT:-18080}"
CONTROL_PORT="${BUILDER_CONTROL_PORT:-8081}"
DATA_PORT="${BUILDER_DATA_PORT:-8082}"
PORTAL_PORT="${BUILDER_PORTAL_PORT:-8084}"
HANDS_PORT="${BUILDER_HANDS_PORT:-8085}"
PG_PORT="${BUILDER_PG_PORT:-5432}"
PG_CONTAINER="${BUILDER_PG_CONTAINER:-agentscope-dev-pg}"
RESET_DB="${BUILDER_RESET_DB:-0}"

# jdbc profile requires >=32 chars and rejects known short defaults (see InternalTokenStartupValidator)
export BUILDER_LOCAL_DEV="${BUILDER_LOCAL_DEV:-true}"
export BUILDER_INTERNAL_TOKEN="${BUILDER_INTERNAL_TOKEN:-local-dev-internal-token-at-least-32chars}"
export BUILDER_JWT_SECRET="${BUILDER_JWT_SECRET:-builder-default-dev-secret-change-in-production-32chars}"
export BUILDER_VAULT_MASTER_KEY="${BUILDER_VAULT_MASTER_KEY:-local-dev-stable-vault-key-at-least-32chars}"
export BUILDER_SESSION_PORTAL_URL="http://localhost:${PORTAL_PORT}"
export BUILDER_WORKSPACE_ROOT="$RUN_DIR/workspaces"
export BUILDER_JPA_DDL_AUTO=validate
export BUILDER_SCHEMA_INIT=false
CONTROL_DB_PASSWORD="${CONTROL_DB_PASSWORD:-local-control-db-password}"
PORTAL_DB_PASSWORD="${PORTAL_DB_PASSWORD:-local-portal-db-password}"
DATA_DB_PASSWORD="${DATA_DB_PASSWORD:-local-data-db-password}"
HANDS_DB_PASSWORD="${HANDS_DB_PASSWORD:-local-hands-db-password}"
export BUILDER_HANDS_URL="http://localhost:${HANDS_PORT}"
export SERVER_ADDRESS="127.0.0.1"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-jdbc}"

DB_URL="jdbc:postgresql://localhost:${PG_PORT}/builder?currentSchema=dp"
CONTROL_PLANE_DSN="postgres://builder:builder@localhost:${PG_PORT}/builder?sslmode=disable"
CONTROL_PLANE_RUNTIME_DSN="${CONTROL_PLANE_DSN}&search_path=rt"

jar_of() {
    find "$ROOT/$1/target" -maxdepth 1 -name "$1-*.jar" \
        ! -name "*sources*" ! -name "*javadoc*" | head -1
}

managed_plane_name() {
    local pid="$1" command
    command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    [ -n "$command" ] || return 1
    case "$command" in
        *"$ROOT/service-controlplane/bin/service-controlplane"*) echo control ;;
        *"$ROOT/service-session-portal/target/service-session-portal-"*.jar*) echo portal ;;
        *"$ROOT/service-hands/target/service-hands-"*.jar*) echo hands ;;
        *"$ROOT/service-dataplane/target/service-dataplane-"*.jar*) echo data ;;
        *"$ROOT/service-gateway/target/service-gateway-"*.jar*) echo gateway ;;
        *) return 1 ;;
    esac
}

describe_pid() {
    local pid="$1" command
    command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    [ -n "$command" ] || command="<process exited>"
    echo "pid ${pid}: ${command}"
}

stop_managed_pid() {
    local port="$1" pid="$2" plane
    plane="$(managed_plane_name "$pid")" || return 1
    echo "  * freeing :${port} from stale ${plane} plane (pid ${pid})"
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 10); do
        kill -0 "$pid" 2>/dev/null || return 0
        sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
}

free_port() {
    local port="$1" pids pid blocked=0
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    [ -n "$pids" ] || return 0
    for pid in $pids; do
        if managed_plane_name "$pid" >/dev/null; then
            stop_managed_pid "$port" "$pid"
        else
            echo "  ERROR :${port} is already in use ($(describe_pid "$pid"))" >&2
            blocked=1
        fi
    done
    [ "$blocked" = "0" ]
}

ensure_service_ports_available() {
    local blocked=0 port
    for port in "$GATEWAY_PORT" "$CONTROL_PORT" "$DATA_PORT" "$PORTAL_PORT" "$HANDS_PORT"; do
        free_port "$port" || blocked=1
    done
    if [ "$blocked" != "0" ]; then
        echo "Another application or Docker container owns an AgentScope Service port." >&2
        echo "Stop that workload or override BUILDER_GATEWAY_PORT/CONTROL_PORT/DATA_PORT/PORTAL_PORT/HANDS_PORT." >&2
        return 1
    fi
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
    local name="$1" pidfile="$2" pid plane; shift 2
    mkdir -p "$LOG_DIR" "$PID_DIR"
    if [ -f "$pidfile" ]; then
        pid="$(cat "$pidfile")"
        if kill -0 "$pid" 2>/dev/null; then
            if plane="$(managed_plane_name "$pid")" && [ "$plane" = "$name" ]; then
                echo "  * ${name} already running (pid ${pid})"
                return 0
            fi
            echo "  * ignoring stale ${name} PID file ($(describe_pid "$pid"))" >&2
        fi
        rm -f "$pidfile"
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
    STARTED_PLANES=1
    echo "  * ${name} started (pid $!)"
}

: "${BUILDER_MANAGED_REDIS_URI:?Set Redis URI before starting Managed services}"
: "${BUILDER_MANAGED_MQ_ENDPOINTS:?Set RocketMQ 5.5.1 Cluster Proxy endpoints}"

# Check before expensive builds or database changes. On macOS, Docker Desktop
# itself listens on ports published by containers, so arbitrary port-based kills
# can terminate the entire Docker engine.
ensure_service_ports_available

# ---------------------------------------------------------------- build Java
# Always install from the monorepo root (not agentscope-service/ alone):
# fat jars embed ~/.m2 harness/core/extensions; a service-only build can keep a
# stale snapshot. Root `mvn install` also walks agentscope-service children
# (unlike `-pl agentscope-service`, which only builds the packaging=pom aggregator).
if [ "${BUILDER_REBUILD:-0}" = "1" ] || [ ! -f "$(jar_of service-gateway || true)" ] || [ ! -f "$(jar_of service-session-portal || true)" ] || [ ! -f "$(jar_of service-dataplane || true)" ] || [ ! -f "$(jar_of service-hands || true)" ]; then
    echo "==> Building agentscope-java monorepo (mvn install -DskipTests)"
    MONOREPO_ROOT="$(cd "$ROOT/.." && pwd)"
    (cd "$MONOREPO_ROOT" && mvn -T1 install -DskipTests -q)
fi

# ---------------------------------------------------------------- build console
# Generated UI assets are no longer tracked; a fresh clone must build the SPA.
if [ "${BUILDER_REBUILD:-0}" = "1" ] || [ ! -f "$ROOT/service-controlplane/ui/index.html" ]; then
    echo "==> Building console from frontend sources"
    (cd "$ROOT/frontend" && npm ci && npm run build)
fi

# ---------------------------------------------------------------- build service-controlplane
CONTROL_PLANE_BIN="$ROOT/service-controlplane/bin/service-controlplane"
if [ "${BUILDER_REBUILD:-0}" = "1" ] || [ ! -x "$CONTROL_PLANE_BIN" ]; then
    echo "==> Building service-controlplane"
    (cd "$ROOT/service-controlplane" && mkdir -p bin && go build -o bin/service-controlplane ./cmd/service-controlplane)
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
            -p "127.0.0.1:${PG_PORT}:5432" \
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

# v4 intentionally has no compatibility migration from the unpublished legacy
# Issue/OrchestrationRun/AgentTask/ExecutionAttempt schema. Rebuilds preserve data by default.
# Set BUILDER_RESET_DB=1 only to recreate this disposable development database's schemas.
if [ "$RESET_DB" = "1" ]; then
    echo "==> Resetting disposable Postgres schemas cp, rt, portal, dp, ma"
    docker exec "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U builder -d builder -c \
        "DROP SCHEMA IF EXISTS cp CASCADE; DROP SCHEMA IF EXISTS rt CASCADE; DROP SCHEMA IF EXISTS portal CASCADE; DROP SCHEMA IF EXISTS dp CASCADE; DROP SCHEMA IF EXISTS ma CASCADE;" >/dev/null
fi

# Apply the bootstrap on every start, not only when Docker first creates the
# volume. This also restores grants and the role search_path after a reset.
docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U builder -d builder \
    <"$ROOT/docker/postgres-init.sql" >/dev/null

schema_count="$(docker exec "$PG_CONTAINER" psql -U builder -d builder -Atc \
    "SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('cp','rt','portal','dp','ma')")"
if [ "$schema_count" != "5" ]; then
    echo "Expected cp, rt, portal, dp, and ma schemas, found ${schema_count}" >&2
    exit 1
fi

# ---------------------------------------------------------------- independent schema jobs
CONTROL_PLANE_PRODUCT_DSN="$CONTROL_PLANE_DSN" "$CONTROL_PLANE_BIN" \
    --storage-driver=postgres --storage-dsn="$CONTROL_PLANE_RUNTIME_DSN" --migrate-only \
    >"$LOG_DIR/schema-control.log" 2>&1
for component in portal dataplane managed; do
    module=service-dataplane
    schema=dp
    [ "$component" != portal ] || { module=service-session-portal; schema=portal; }
    [ "$component" != managed ] || { module=service-session-portal; schema=ma; }
    BUILDER_PORTAL_DB_PASSWORD=builder BUILDER_DB_PASSWORD=builder BUILDER_MANAGED_DB_PASSWORD=builder \
    java -Dloader.main=io.agentscope.builder.web.persistence.ServiceSchemaMain \
        -cp "$(jar_of "$module")" org.springframework.boot.loader.launch.PropertiesLauncher \
        "--component=$component" "--schema=$schema" \
        "--url=jdbc:postgresql://localhost:${PG_PORT}/builder" --username=builder \
        >"$LOG_DIR/schema-${component}.log" 2>&1
done
docker exec -i -e PGHOST=127.0.0.1 -e PGDATABASE=builder -e PGUSER=builder -e PGPASSWORD=builder \
    -e "CONTROL_DB_PASSWORD=$CONTROL_DB_PASSWORD" \
    -e "PORTAL_DB_PASSWORD=$PORTAL_DB_PASSWORD" -e "DATA_DB_PASSWORD=$DATA_DB_PASSWORD" \
    -e "HANDS_DB_PASSWORD=$HANDS_DB_PASSWORD" "$PG_CONTAINER" sh -s \
    <"$ROOT/deploy/postgres-roles.sh" >"$LOG_DIR/schema-grants.log" 2>&1
CONTROL_PLANE_DSN="postgres://agentscope_control:${CONTROL_DB_PASSWORD}@localhost:${PG_PORT}/builder?sslmode=disable"
CONTROL_PLANE_RUNTIME_DSN="${CONTROL_PLANE_DSN}&search_path=rt"

# ---------------------------------------------------------------- planes
mkdir -p "$LOG_DIR" "$PID_DIR"

echo "==> Starting planes (Postgres: ${DB_URL})"

start control "$PID_DIR/control.pid" \
    env CONTROL_PLANE_ENABLE_KUBERNETES=false \
        CONTROL_PLANE_PRODUCT_DSN="$CONTROL_PLANE_DSN" \
        CONTROL_PLANE_HTTP_BIND="127.0.0.1:${CONTROL_PORT}" \
        BUILDER_JWT_SECRET="$BUILDER_JWT_SECRET" \
        BUILDER_INTERNAL_TOKEN="$BUILDER_INTERNAL_TOKEN" \
        BUILDER_DATA_URL="http://localhost:${DATA_PORT}" \
        BUILDER_ALLOW_LOCAL_ENVIRONMENT=true \
        CONTROL_PLANE_WORKSPACE_ROOT="$RUN_DIR/workspaces" \
        CONTROL_PLANE_ARTIFACT_ROOT="$RUN_DIR/artifacts" \
        CONTROL_PLANE_STATIC_DIR="$ROOT/service-controlplane/ui" \
    "$CONTROL_PLANE_BIN" \
        --storage-driver=postgres \
        --storage-dsn="$CONTROL_PLANE_RUNTIME_DSN" \
        --schema-mode=validate --enable-runtime-workers=true --log-format=console

start portal "$PID_DIR/portal.pid" \
    env BUILDER_PORTAL_DB_URL="jdbc:postgresql://localhost:${PG_PORT}/builder?currentSchema=portal" \
        BUILDER_PORTAL_DB_USER=agentscope_portal BUILDER_PORTAL_DB_PASSWORD="$PORTAL_DB_PASSWORD" \
        BUILDER_PORTAL_SCHEMA_INIT=never BUILDER_PORTAL_PORT="$PORTAL_PORT" \
        BUILDER_CONTROL_URL="http://localhost:${CONTROL_PORT}" \
        BUILDER_DATA_URL="http://localhost:${DATA_PORT}" \
    java -jar "$(jar_of service-session-portal)"

start data "$PID_DIR/data.pid" \
    env BUILDER_DB_URL="$DB_URL" BUILDER_DB_DRIVER=org.postgresql.Driver \
        BUILDER_DB_USER=agentscope_data BUILDER_DB_PASSWORD="$DATA_DB_PASSWORD" \
        BUILDER_MANAGED_CONSUMERS_ENABLED=true \
        BUILDER_DATA_PORT="$DATA_PORT" \
        BUILDER_CONTROL_URL="http://localhost:${CONTROL_PORT}" \
        BUILDER_INTERNAL_TOKEN="$BUILDER_INTERNAL_TOKEN" \
        BUILDER_JWT_SECRET="$BUILDER_JWT_SECRET" \
    java -jar "$(jar_of service-dataplane)"

start gateway "$PID_DIR/gateway.pid" \
    env BUILDER_GATEWAY_PORT="$GATEWAY_PORT" \
        BUILDER_CONTROL_URL="http://localhost:${CONTROL_PORT}" \
        BUILDER_DATA_URL="http://localhost:${DATA_PORT}" \
    java -jar "$(jar_of service-gateway)"

start hands "$PID_DIR/hands.pid" \
    env BUILDER_HANDS_DB_URL="jdbc:postgresql://localhost:${PG_PORT}/builder?currentSchema=ma" \
        BUILDER_HANDS_DB_USER=agentscope_hands BUILDER_HANDS_DB_PASSWORD="$HANDS_DB_PASSWORD" \
        BUILDER_HANDS_PORT="$HANDS_PORT" BUILDER_CONTROL_URL="http://localhost:${CONTROL_PORT}" \
    java -jar "$(jar_of service-hands)" --platform-service

echo "==> Waiting for health"
wait_health control "$CONTROL_PORT" 60 /healthz
wait_health portal "$PORTAL_PORT"
wait_health data "$DATA_PORT"
wait_health hands "$HANDS_PORT"
wait_health gateway "$GATEWAY_PORT" 30

echo "==> Verifying database schemas and v4 terminal migrations"
docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U builder -d builder \
    <"$ROOT/docker/postgres-dev-verify.sql" >/dev/null
echo "  OK cp/rt/portal/dp/ma schemas and current execution tables"

if [ "${BUILDER_SMOKE_TEST:-0}" = "1" ]; then
    echo "==> Running API smoke test"
    BASE="http://localhost:${GATEWAY_PORT}" "$ROOT/scripts/smoke.sh"
fi

STARTUP_SUCCEEDED=1

cat <<EOF

==> AgentScope Service stack is up (Control (Go orchestration, Cron and channels) + Portal + Harness + Hands + Gateway)

  Console (SPA via gateway):  http://localhost:${GATEWAY_PORT}/
  Identity:                  local developer when BUILDER_LOCAL_DEV=true

  Frontend HMR (optional):    cd frontend && npm run dev

  Logs:   ${LOG_DIR}
  Verify: scripts/smoke.sh
  Stop:   scripts/dev-down.sh
EOF
