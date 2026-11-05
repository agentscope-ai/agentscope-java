#!/usr/bin/env bash
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
set -euo pipefail
service_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${BUILDER_COMPOSE_ENV_FILE:-$service_dir/deploy/.env}"
if [[ ! -f "$env_file" ]]; then
    echo "Missing deployment configuration: $env_file; run deploy/init-env.sh first." >&2
    exit 2
fi
args=(--project-name "${BUILDER_COMPOSE_PROJECT:-agentscope-service}" \
      --env-file "$env_file" -f "${BUILDER_COMPOSE_FILE:-$service_dir/deploy/compose.yaml}")
if [[ -n "${BUILDER_COMPOSE_OVERRIDE:-}" ]]; then
    args+=(-f "$BUILDER_COMPOSE_OVERRIDE")
fi
exec docker compose "${args[@]}" "$@"
