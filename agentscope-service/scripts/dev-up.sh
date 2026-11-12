#!/usr/bin/env bash
#
# dev-up.sh - start the Managed Agents stack locally.
#
#   Gateway    :18080
#   control-plane    :8081  (Go control plane: /api/*, /api/v1/*, console SPA)
#   Data       :8082
#   Agent Service     :8084
#   Go Runtime :8086
#   Tool Worker      :8085 (platform HTTP/MQ service)
#   Postgres   :5432  (schemas cp + rt + portal + dp + ma; via Docker)
#
# control-plane runs standalone here (CONTROL_PLANE_ENABLE_KUBERNETES=false), so no
# reconcilers, CRD-backed APIs, or RuntimeProtocol gRPC listener are started.
#
# Usage:
#   scripts/dev-up.sh
#   MANAGED_AGENTS_REBUILD=1 scripts/dev-up.sh   # rebuild binaries and reset the disposable dev schemas
#   MANAGED_AGENTS_REBUILD=1 MANAGED_AGENTS_RESET_DB=0 scripts/dev-up.sh  # rebuild while preserving local data
#   scripts/dev-down.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${MANAGED_AGENTS_RUN_DIR:-$ROOT/.dev-stack}"
LOG_DIR="$RUN_DIR/logs"
PID_DIR="$RUN_DIR/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

STARTUP_SUCCEEDED=0
cleanup_failed_startup() {
    local status=$?
    if [ "$status" -ne 0 ] && [ "$STARTUP_SUCCEEDED" != "1" ] && [ "${MANAGED_AGENTS_KEEP_FAILED_STACK:-0}" != "1" ]; then
        echo "==> Startup failed; stopping partially started planes" >&2
        "$ROOT/scripts/dev-down.sh" || true
    fi
}
trap cleanup_failed_startup EXIT

GATEWAY_PORT="${MANAGED_AGENTS_GATEWAY_PORT:-18080}"
CONTROL_PORT="${MANAGED_AGENTS_CONTROL_PORT:-8081}"
AGENT_HARNESS_RUNTIME_PORT="${MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_PORT:-8082}"
AGENT_SERVICE_PORT="${MANAGED_AGENTS_AGENT_SERVICE_PORT:-8084}"
TOOL_WORKER_PORT="${MANAGED_AGENTS_TOOL_WORKER_PORT:-8085}"
PG_PORT="${MANAGED_AGENTS_PG_PORT:-5432}"
PG_CONTAINER="${MANAGED_AGENTS_PG_CONTAINER:-managed-agents-dev-pg}"
RESET_DB="${MANAGED_AGENTS_RESET_DB:-${MANAGED_AGENTS_REBUILD:-0}}"

# jdbc profile requires >=32 chars and rejects known short defaults (see InternalTokenStartupValidator)
export MANAGED_AGENTS_LOCAL_DEV="${MANAGED_AGENTS_LOCAL_DEV:-true}"
export MANAGED_AGENTS_INTERNAL_TOKEN="${MANAGED_AGENTS_INTERNAL_TOKEN:-local-dev-internal-token-at-least-32chars}"
export MANAGED_AGENTS_JWT_SECRET="${MANAGED_AGENTS_JWT_SECRET:-managed-agents-default-dev-secret-change-in-production-32chars}"
export MANAGED_AGENTS_VAULT_MASTER_KEY="${MANAGED_AGENTS_VAULT_MASTER_KEY:-local-dev-stable-vault-key-at-least-32chars}"
export MANAGED_AGENTS_AGENT_SERVICE_URL="http://localhost:${AGENT_SERVICE_PORT}"
export MANAGED_AGENTS_SESSION_SERVICE_URL="${MANAGED_AGENTS_SESSION_SERVICE_URL:-${MANAGED_AGENTS_AGENT_SERVICE_URL}/internal/session-service/v0}"
export MANAGED_AGENTS_WORKSPACE_ROOT="$RUN_DIR/workspaces"
export MANAGED_AGENTS_JPA_DDL_AUTO=validate
export MANAGED_AGENTS_SCHEMA_INIT=false
CONTROL_DB_PASSWORD="${CONTROL_DB_PASSWORD:-local-control-db-password}"
AGENT_SERVICE_DB_PASSWORD="${AGENT_SERVICE_DB_PASSWORD:-local-agent-service-db-password}"
AGENT_HARNESS_RUNTIME_DB_PASSWORD="${AGENT_HARNESS_RUNTIME_DB_PASSWORD:-local-agent-harness-runtime-db-password}"
TOOL_WORKER_DB_PASSWORD="${TOOL_WORKER_DB_PASSWORD:-local-tool-worker-db-password}"
export MANAGED_AGENTS_TOOL_WORKER_URL="http://localhost:${TOOL_WORKER_PORT}"
export MANAGED_AGENTS_RUNTIME_BLOB_DIR="$RUN_DIR/managed-runtime-blobs"
export SERVER_ADDRESS="127.0.0.1"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-jdbc}"

DB_URL="jdbc:postgresql://localhost:${PG_PORT}/managed_agents?currentSchema=dp"
CONTROL_PLANE_DSN="postgres://managed_agents:managed_agents@localhost:${PG_PORT}/managed_agents?sslmode=disable"
CONTROL_PLANE_RUNTIME_DSN="${CONTROL_PLANE_DSN}&search_path=rt"

jar_of() {
    local module_path="$1"
    [ "$1" != agent-harness-runtime ] || module_path="agent-harness-runtime/java/brain-agentscope"
    local jar_pattern="managed-agents-$1-*.jar"
    [ "$1" != tool-worker ] || jar_pattern="managed-agents-tool-worker-*-exec.jar"
    find "$ROOT/$module_path/target" -maxdepth 1 -name "$jar_pattern" \
        ! -name "*sources*" ! -name "*javadoc*" | head -1
}

managed_plane_name() {
    local pid="$1" command
    command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    [ -n "$command" ] || return 1
    case "$command" in
        *"$ROOT/control-plane/bin/control-plane"*) echo control ;;
        *"$ROOT/agent-service/target/managed-agents-agent-service-"*.jar*) echo agent-service ;;
        *"$ROOT/tool-worker/target/managed-agents-tool-worker-"*.jar*) echo tool-worker ;;
        *"$ROOT/agent-harness-runtime/java/brain-agentscope/target/managed-agents-agent-harness-runtime-"*.jar*) echo agent-harness-runtime ;;
        *"$ROOT/gateway/target/managed-agents-gateway-"*.jar*) echo gateway ;;
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
    for port in "$GATEWAY_PORT" "$CONTROL_PORT" "$AGENT_HARNESS_RUNTIME_PORT" "$AGENT_SERVICE_PORT" "$TOOL_WORKER_PORT"; do
        free_port "$port" || blocked=1
    done
    if [ "$blocked" != "0" ]; then
        echo "Another application or Docker container owns an Managed Agents port." >&2
        echo "Stop that workload or override MANAGED_AGENTS_GATEWAY_PORT/CONTROL_PORT/AGENT_HARNESS_RUNTIME_PORT/SCHEDULER_PORT." >&2
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
    echo "  * ${name} started (pid $!)"
}

: "${MANAGED_AGENTS_RUNTIME_REDIS_URI:?Set Redis URI before starting Managed services}"
: "${MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS:?Set RocketMQ 5.5.1 Cluster Proxy endpoints}"

# Check before expensive builds or database changes. On macOS, Docker Desktop
# itself listens on ports published by containers, so arbitrary port-based kills
# can terminate the entire Docker engine.
ensure_service_ports_available

# ---------------------------------------------------------------- build Java
# Build this standalone reactor; AgentScope Java libraries resolve as Maven artifacts.
if [ "${MANAGED_AGENTS_REBUILD:-0}" = "1" ] || [ ! -f "$(jar_of gateway || true)" ] || [ ! -f "$(jar_of agent-service || true)" ] || [ ! -f "$(jar_of tool-worker || true)" ] || [ ! -f "$(jar_of agent-harness-runtime || true)" ]; then
    echo "==> Building standalone Service project (mvn install -DskipTests)"
    PROJECT_ROOT="$(cd "$ROOT/.." && pwd)"
    (cd "$PROJECT_ROOT" && mvn -T1 install -DskipTests -q)
fi

# ---------------------------------------------------------------- build console
# Generated UI assets are no longer tracked; a fresh clone must build the SPA.
if [ "${MANAGED_AGENTS_REBUILD:-0}" = "1" ] || [ ! -f "$ROOT/control-plane/ui/index.html" ]; then
    echo "==> Building console from console sources"
    (cd "$ROOT/console" && npm ci && npm run build)
fi

# ---------------------------------------------------------------- build control-plane
CONTROL_PLANE_BIN="$ROOT/control-plane/bin/control-plane"
if [ "${MANAGED_AGENTS_REBUILD:-0}" = "1" ] || [ ! -x "$CONTROL_PLANE_BIN" ]; then
    echo "==> Building control-plane"
    (cd "$ROOT/control-plane" && mkdir -p bin && go build -o bin/control-plane ./cmd/control-plane)
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
            -e POSTGRES_DB=managed_agents \
            -e POSTGRES_USER=managed_agents \
            -e POSTGRES_PASSWORD=managed_agents \
            -p "${PG_PORT}:5432" \
            -v "$ROOT/docker/postgres-init.sql:/docker-entrypoint-initdb.d/01-schemas.sql:ro" \
            postgres:17 >/dev/null
    fi
fi

echo "==> Waiting for Postgres"
for i in $(seq 1 60); do
    if docker exec "$PG_CONTAINER" pg_isready -U managed_agents -d managed_agents >/dev/null 2>&1; then
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
# Issue/OrchestrationRun/AgentTask/ExecutionAttempt schema. A full local rebuild therefore recreates the
# disposable development schemas before either Hibernate or control-plane starts.
# Set MANAGED_AGENTS_RESET_DB=0 explicitly when the current v4 development data should be kept.
if [ "$RESET_DB" = "1" ]; then
    echo "==> Resetting disposable Postgres schemas cp, rt, portal, dp, ma"
    docker exec "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U managed_agents -d managed_agents -c \
        "DROP SCHEMA IF EXISTS cp CASCADE; DROP SCHEMA IF EXISTS rt CASCADE; DROP SCHEMA IF EXISTS portal CASCADE; DROP SCHEMA IF EXISTS dp CASCADE; DROP SCHEMA IF EXISTS ma CASCADE;" >/dev/null
fi

# Apply the bootstrap on every start, not only when Docker first creates the
# volume. This also restores grants and the role search_path after a reset.
docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U managed_agents -d managed_agents \
    <"$ROOT/docker/postgres-init.sql" >/dev/null

schema_count="$(docker exec "$PG_CONTAINER" psql -U managed_agents -d managed_agents -Atc \
    "SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('cp','rt','portal','dp','ma')")"
if [ "$schema_count" != "5" ]; then
    echo "Expected cp, rt, portal, dp, and ma schemas, found ${schema_count}" >&2
    exit 1
fi

# Schema 3 public projections use a separate local database. Production should budget
# query storage independently; this shared development container is not a load-test baseline.
if ! docker exec "$PG_CONTAINER" psql -U managed_agents -d managed_agents -Atc \
    "SELECT 1 FROM pg_database WHERE datname='managed_agents_query'" | grep -qx 1; then
    docker exec "$PG_CONTAINER" createdb -U managed_agents managed_agents_query
fi
if [ "$RESET_DB" = "1" ]; then
    docker exec "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U managed_agents -d managed_agents_query \
        -c "DROP SCHEMA IF EXISTS ma_query CASCADE" >/dev/null
fi
docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U managed_agents -d managed_agents_query \
    <"$ROOT/common/src/main/resources/managed-query-store.sql" >/dev/null
QUERY_ENV=(MANAGED_AGENTS_QUERY_STORE_ENABLED=true MANAGED_AGENTS_QUERY_STORE_PROVIDER=postgres
    "MANAGED_AGENTS_QUERY_STORE_JDBC_URL=jdbc:postgresql://localhost:${PG_PORT}/managed_agents_query"
    MANAGED_AGENTS_QUERY_STORE_USER=managed_agents MANAGED_AGENTS_QUERY_STORE_PASSWORD=managed_agents
    MANAGED_AGENTS_QUERY_STORE_SIGNING_KEY=local-query-cursor-key-at-least-32-characters
    MANAGED_AGENTS_QUERY_STORE_BUILD=v3)

# ---------------------------------------------------------------- independent schema jobs
CONTROL_PLANE_PRODUCT_DSN="$CONTROL_PLANE_DSN" "$CONTROL_PLANE_BIN" \
    --storage-driver=postgres --storage-dsn="$CONTROL_PLANE_RUNTIME_DSN" --migrate-only \
    >"$LOG_DIR/schema-control.log" 2>&1
for component in agent-service agent-harness-runtime runtime; do
    module=agent-harness-runtime
    schema=dp
    [ "$component" != agent-service ] || { module=agent-service; schema=portal; }
    [ "$component" != runtime ] || { module=agent-service; schema=ma; }
    MANAGED_AGENTS_AGENT_SERVICE_DB_PASSWORD=managed_agents MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_DB_PASSWORD=managed_agents MANAGED_AGENTS_RUNTIME_DB_PASSWORD=managed_agents \
    java -Dloader.main=io.agentcore.managed.server.web.persistence.ServiceSchemaMain \
        -cp "$(jar_of "$module")" org.springframework.boot.loader.launch.PropertiesLauncher \
        "--component=$component" "--schema=$schema" \
        "--url=jdbc:postgresql://localhost:${PG_PORT}/managed_agents" --username=managed_agents \
        >"$LOG_DIR/schema-${component}.log" 2>&1
done
docker exec -i -e PGHOST=127.0.0.1 -e PGDATABASE=managed_agents -e PGUSER=managed_agents -e PGPASSWORD=managed_agents \
    -e "CONTROL_DB_PASSWORD=$CONTROL_DB_PASSWORD" \
    -e "AGENT_SERVICE_DB_PASSWORD=$AGENT_SERVICE_DB_PASSWORD" -e "AGENT_HARNESS_RUNTIME_DB_PASSWORD=$AGENT_HARNESS_RUNTIME_DB_PASSWORD" \
    -e "TOOL_WORKER_DB_PASSWORD=$TOOL_WORKER_DB_PASSWORD" "$PG_CONTAINER" sh -s \
    <"$ROOT/deploy/postgres-roles.sh" >"$LOG_DIR/schema-grants.log" 2>&1
CONTROL_PLANE_DSN="postgres://managed_agents_control:${CONTROL_DB_PASSWORD}@localhost:${PG_PORT}/managed_agents?sslmode=disable"
CONTROL_PLANE_RUNTIME_DSN="${CONTROL_PLANE_DSN}&search_path=rt"

# ---------------------------------------------------------------- planes
mkdir -p "$LOG_DIR" "$PID_DIR"

echo "==> Starting planes (Postgres: ${DB_URL})"

start control "$PID_DIR/control.pid" \
    env CONTROL_PLANE_ENABLE_KUBERNETES=false \
        CONTROL_PLANE_PRODUCT_DSN="$CONTROL_PLANE_DSN" \
        CONTROL_PLANE_HTTP_BIND="127.0.0.1:${CONTROL_PORT}" \
        MANAGED_AGENTS_JWT_SECRET="$MANAGED_AGENTS_JWT_SECRET" \
        MANAGED_AGENTS_INTERNAL_TOKEN="$MANAGED_AGENTS_INTERNAL_TOKEN" \
        MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_URL="http://localhost:${AGENT_HARNESS_RUNTIME_PORT}" \
        MANAGED_AGENTS_ALLOW_LOCAL_ENVIRONMENT=true \
        CONTROL_PLANE_WORKSPACE_ROOT="$RUN_DIR/workspaces" \
        CONTROL_PLANE_ARTIFACT_ROOT="$RUN_DIR/artifacts" \
        CONTROL_PLANE_STATIC_DIR="$ROOT/control-plane/ui" \
    "$CONTROL_PLANE_BIN" \
        --storage-driver=postgres \
        --storage-dsn="$CONTROL_PLANE_RUNTIME_DSN" \
        --schema-mode=validate --enable-runtime-workers=true --log-format=console

start agent-service "$PID_DIR/agent-service.pid" \
    env "${QUERY_ENV[@]}" MANAGED_AGENTS_AGENT_SERVICE_DB_URL="jdbc:postgresql://localhost:${PG_PORT}/managed_agents?currentSchema=portal" \
        MANAGED_AGENTS_AGENT_SERVICE_DB_USER=managed_agents_agent_service MANAGED_AGENTS_AGENT_SERVICE_DB_PASSWORD="$AGENT_SERVICE_DB_PASSWORD" \
        MANAGED_AGENTS_BRAIN_HTTP_ENABLED=true \
        MANAGED_AGENTS_BRAIN_CALLBACK_URL="http://localhost:${AGENT_HARNESS_RUNTIME_PORT}/api/internal/brain/notify" \
        MANAGED_AGENTS_AGENT_SERVICE_SCHEMA_INIT=never MANAGED_AGENTS_AGENT_SERVICE_PORT="$AGENT_SERVICE_PORT" \
        MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_URL="http://localhost:${AGENT_HARNESS_RUNTIME_PORT}" \
    java -jar "$(jar_of agent-service)"

start agent-harness-runtime "$PID_DIR/agent-harness-runtime.pid" \
    env -u MANAGED_AGENTS_RUNTIME_REDIS_URI -u MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS \
        SPRING_PROFILES_ACTIVE= \
        MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_PORT="$AGENT_HARNESS_RUNTIME_PORT" \
        MANAGED_AGENTS_INTERNAL_TOKEN="$MANAGED_AGENTS_INTERNAL_TOKEN" \
        MANAGED_AGENTS_JWT_SECRET="$MANAGED_AGENTS_JWT_SECRET" \
    java -jar "$(jar_of agent-harness-runtime)"

start gateway "$PID_DIR/gateway.pid" \
    env MANAGED_AGENTS_GATEWAY_PORT="$GATEWAY_PORT" \
        MANAGED_AGENTS_CONTROL_URL="http://localhost:${CONTROL_PORT}" \
        MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_URL="http://localhost:${AGENT_HARNESS_RUNTIME_PORT}" \
    java -jar "$(jar_of gateway)"

start tool-worker "$PID_DIR/tool-worker.pid" \
    env MANAGED_AGENTS_TOOL_WORKER_DB_URL="jdbc:postgresql://localhost:${PG_PORT}/managed_agents?currentSchema=ma" \
        MANAGED_AGENTS_TOOL_WORKER_DB_USER=managed_agents_tool_worker MANAGED_AGENTS_TOOL_WORKER_DB_PASSWORD="$TOOL_WORKER_DB_PASSWORD" \
        MANAGED_AGENTS_TOOL_WORKER_PORT="$TOOL_WORKER_PORT" MANAGED_AGENTS_CONTROL_URL="http://localhost:${CONTROL_PORT}" \
    java -jar "$(jar_of tool-worker)" --platform-service

echo "==> Waiting for health"
wait_health control "$CONTROL_PORT" 60 /healthz
wait_health agent-service "$AGENT_SERVICE_PORT"
wait_health agent-harness-runtime "$AGENT_HARNESS_RUNTIME_PORT"
wait_health tool-worker "$TOOL_WORKER_PORT"
wait_health gateway "$GATEWAY_PORT" 30

echo "==> Verifying database schemas and v4 terminal migrations"
docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U managed_agents -d managed_agents \
    <"$ROOT/docker/postgres-dev-verify.sql" >/dev/null
echo "  OK cp/rt/dp schemas and v4 collaboration/orchestration/runtime tables"

if [ "${MANAGED_AGENTS_SMOKE_TEST:-0}" = "1" ]; then
    echo "==> Running API smoke test"
    BASE="http://localhost:${GATEWAY_PORT}" "$ROOT/scripts/smoke.sh"
fi

STARTUP_SUCCEEDED=1

cat <<EOF

==> Managed Agents stack is up (Control (Go orchestration, Cron and channels) + Agent Service + Harness + Tool Worker + Gateway)

  Console (SPA via gateway):  http://localhost:${GATEWAY_PORT}/
  Default login:              admin / admin

  Frontend HMR (optional):    cd console && npm run dev

  Logs:   ${LOG_DIR}
  Verify: scripts/smoke.sh
  Stop:   scripts/dev-down.sh
EOF
