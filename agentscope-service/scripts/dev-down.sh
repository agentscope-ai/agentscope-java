#!/usr/bin/env bash
#
# dev-down.sh - stop the stack started by dev-up.sh (planes + optional Postgres container).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${MANAGED_AGENTS_RUN_DIR:-$ROOT/.dev-stack}"
PID_DIR="$RUN_DIR/pids"
PG_CONTAINER="${MANAGED_AGENTS_PG_CONTAINER:-managed-agents-dev-pg}"

GATEWAY_PORT="${MANAGED_AGENTS_GATEWAY_PORT:-18080}"
CONTROL_PORT="${MANAGED_AGENTS_CONTROL_PORT:-8081}"
AGENT_HARNESS_RUNTIME_PORT="${MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_PORT:-8082}"
SCHED_PORT="${MANAGED_AGENTS_SCHEDULER_PORT:-8083}"
AGENT_SERVICE_PORT="${MANAGED_AGENTS_AGENT_SERVICE_PORT:-8084}"
RUNTIME_PORT="${MANAGED_AGENTS_ORCHESTRATION_RUNTIME_PORT:-8086}"
TOOL_WORKER_PORT="${MANAGED_AGENTS_TOOL_WORKER_PORT:-8085}"

managed_plane_name() {
    local pid="$1" command
    command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    [ -n "$command" ] || return 1
    case "$command" in
        *"$ROOT/control-plane/bin/control-plane"*) echo control ;;
        *"$ROOT/control-plane/bin/managed-agents-orchestration-runtime"*) echo orchestration ;;
        *"$ROOT/agent-service/target/managed-agents-agent-service-"*.jar*) echo agent-service ;;
        *"$ROOT/tool-worker/target/managed-agents-tool-worker-"*.jar*) echo tool-worker ;;
        *"$ROOT/agent-harness-runtime/java/brain-agentscope/target/managed-agents-agent-harness-runtime-"*.jar*) echo agent-harness-runtime ;;
        *"$ROOT/service-scheduler/target/service-scheduler-"*.jar*) echo scheduler ;;
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

kill_pid() {
    local name="$1" pid="$2" actual_name
    [ -n "$pid" ] || return 0
    if kill -0 "$pid" 2>/dev/null; then
        if ! actual_name="$(managed_plane_name "$pid")"; then
            echo "  * refusing to stop ${name}; PID is not an Managed Agents plane ($(describe_pid "$pid"))" >&2
            return 1
        fi
        if [[ "$name" != port:* ]] && [ "$name" != "$actual_name" ]; then
            echo "  * refusing to stop ${name}; PID belongs to ${actual_name} ($(describe_pid "$pid"))" >&2
            return 1
        fi
        kill "$pid" 2>/dev/null || true
        for _ in $(seq 1 10); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "$pid" 2>/dev/null; then
            kill -9 "$pid" 2>/dev/null || true
        fi
        echo "  OK ${actual_name} stopped (pid ${pid})"
        return 0
    fi
    return 1
}

free_port() {
    local port="$1" pids pid plane
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    [ -n "$pids" ] || return 0
    for pid in $pids; do
        if plane="$(managed_plane_name "$pid")"; then
            if kill_pid "port:${port}" "$pid"; then
                stopped=1
            fi
        else
            echo "  * leaving :${port} listener unchanged ($(describe_pid "$pid"))" >&2
        fi
    done
}

stopped=0
if [ -d "$PID_DIR" ]; then
    for pidfile in "$PID_DIR"/*.pid; do
        [ -e "$pidfile" ] || continue
        name="$(basename "$pidfile" .pid)"
        pid="$(cat "$pidfile")"
        if kill_pid "$name" "$pid"; then
            stopped=1
        elif kill -0 "$pid" 2>/dev/null; then
            echo "  * ${name} PID file is stale; process left unchanged"
        else
            echo "  * ${name} not running"
        fi
        rm -f "$pidfile"
    done
fi

# Stop only identifiable Managed Agents orphans. A Docker-published port is owned by
# com.docker.backend on macOS; killing an arbitrary listener would stop Docker.
for port in "$GATEWAY_PORT" "$CONTROL_PORT" "$AGENT_HARNESS_RUNTIME_PORT" "$SCHED_PORT" "$AGENT_SERVICE_PORT" "$RUNTIME_PORT" "$TOOL_WORKER_PORT"; do
    free_port "$port"
done

if [ "${MANAGED_AGENTS_STOP_PG:-0}" = "1" ] && command -v docker >/dev/null 2>&1; then
    if docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
        docker stop "$PG_CONTAINER" >/dev/null
        echo "  OK Postgres container ${PG_CONTAINER} stopped"
        stopped=1
    fi
fi

[ "$stopped" = "1" ] && echo "==> Stack stopped" || echo "==> Nothing was running"
