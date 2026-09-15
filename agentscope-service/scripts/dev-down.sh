#!/usr/bin/env bash
#
# dev-down.sh - stop the stack started by dev-up.sh (planes + optional Postgres container).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${BUILDER_RUN_DIR:-$ROOT/.dev-stack}"
PID_DIR="$RUN_DIR/pids"
PG_CONTAINER="${BUILDER_PG_CONTAINER:-agentscope-dev-pg}"

GATEWAY_PORT="${BUILDER_GATEWAY_PORT:-18080}"
CONTROL_PORT="${BUILDER_CONTROL_PORT:-8081}"
DATA_PORT="${BUILDER_DATA_PORT:-8082}"
SCHED_PORT="${BUILDER_SCHEDULER_PORT:-8083}"

managed_plane_name() {
    local pid="$1" command
    command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    [ -n "$command" ] || return 1
    case "$command" in
        *"$ROOT/aistio/bin/aistiod"*) echo control ;;
        *"$ROOT/service-dataplane/target/service-dataplane-"*.jar*) echo data ;;
        *"$ROOT/service-scheduler/target/service-scheduler-"*.jar*) echo scheduler ;;
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

kill_pid() {
    local name="$1" pid="$2" actual_name
    [ -n "$pid" ] || return 0
    if kill -0 "$pid" 2>/dev/null; then
        if ! actual_name="$(managed_plane_name "$pid")"; then
            echo "  * refusing to stop ${name}; PID is not an AgentScope Service plane ($(describe_pid "$pid"))" >&2
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

# Stop only identifiable AgentScope orphans. A Docker-published port is owned by
# com.docker.backend on macOS; killing an arbitrary listener would stop Docker.
for port in "$GATEWAY_PORT" "$CONTROL_PORT" "$DATA_PORT" "$SCHED_PORT"; do
    free_port "$port"
done

if [ "${BUILDER_STOP_PG:-0}" = "1" ] && command -v docker >/dev/null 2>&1; then
    if docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
        docker stop "$PG_CONTAINER" >/dev/null
        echo "  OK Postgres container ${PG_CONTAINER} stopped"
        stopped=1
    fi
fi

[ "$stopped" = "1" ] && echo "==> Stack stopped" || echo "==> Nothing was running"
