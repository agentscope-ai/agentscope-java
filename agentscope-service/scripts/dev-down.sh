#!/usr/bin/env bash
#
# dev-down.sh - stop the stack started by dev-up.sh (planes + optional Postgres container).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${BUILDER_RUN_DIR:-$ROOT/.dev-stack}"
PID_DIR="$RUN_DIR/pids"
PG_CONTAINER="${BUILDER_PG_CONTAINER:-agentscope-dev-pg}"

GATEWAY_PORT="${BUILDER_GATEWAY_PORT:-8080}"
CONTROL_PORT="${BUILDER_CONTROL_PORT:-8081}"
DATA_PORT="${BUILDER_DATA_PORT:-8082}"
SCHED_PORT="${BUILDER_SCHEDULER_PORT:-8083}"

kill_pid() {
    local name="$1" pid="$2"
    [ -n "$pid" ] || return 0
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        for _ in $(seq 1 10); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        kill -9 "$pid" 2>/dev/null || true
        echo "  OK ${name} stopped (pid ${pid})"
        return 0
    fi
    return 1
}

free_port() {
    local port="$1" pids
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    [ -n "$pids" ] || return 0
    for pid in $pids; do
        kill_pid "port:${port}" "$pid" || true
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
        else
            echo "  * ${name} not running"
        fi
        rm -f "$pidfile"
    done
fi

# Orphans from earlier runs (pidfiles missing / process replaced) still hold ports.
for port in "$GATEWAY_PORT" "$CONTROL_PORT" "$DATA_PORT" "$SCHED_PORT"; do
    before="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    if [ -n "$before" ]; then
        free_port "$port"
        stopped=1
    fi
done

if [ "${BUILDER_STOP_PG:-0}" = "1" ] && command -v docker >/dev/null 2>&1; then
    if docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
        docker stop "$PG_CONTAINER" >/dev/null
        echo "  OK Postgres container ${PG_CONTAINER} stopped"
        stopped=1
    fi
fi

[ "$stopped" = "1" ] && echo "==> Stack stopped" || echo "==> Nothing was running"
