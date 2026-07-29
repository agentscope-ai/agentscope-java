#!/usr/bin/env bash
#
# dev-down.sh — stop the stack started by dev-up.sh (planes + optional Postgres container).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${BUILDER_RUN_DIR:-$ROOT/.dev-stack}"
PID_DIR="$RUN_DIR/pids"
PG_CONTAINER="${BUILDER_PG_CONTAINER:-agentscope-dev-pg}"

stopped=0
for pidfile in "$PID_DIR"/*.pid; do
    [ -e "$pidfile" ] || continue
    name="$(basename "$pidfile" .pid)"
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        for _ in $(seq 1 10); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        kill -9 "$pid" 2>/dev/null || true
        echo "  ✔ ${name} stopped (pid ${pid})"
        stopped=1
    else
        echo "  • ${name} not running"
    fi
    rm -f "$pidfile"
done

if [ "${BUILDER_STOP_PG:-0}" = "1" ] && command -v docker >/dev/null 2>&1; then
    if docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
        docker stop "$PG_CONTAINER" >/dev/null
        echo "  ✔ Postgres container ${PG_CONTAINER} stopped"
        stopped=1
    fi
fi

[ "$stopped" = "1" ] && echo "==> Stack stopped" || echo "==> Nothing was running"
