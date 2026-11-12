#!/usr/bin/env bash
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
set -euo pipefail
service_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for file in local-agent-service.env local-agent-service.local.yaml; do
    if [[ ! -f "$service_dir/deploy/$file" ]]; then
        echo "Missing local deployment configuration: $service_dir/deploy/$file" >&2
        exit 2
    fi
done
exec docker compose --project-name managed-agents-agent-service-local \
    --env-file "$service_dir/deploy/local-agent-service.env" \
    -f "$service_dir/deploy/compose.yaml" \
    -f "$service_dir/deploy/local-agent-service.local.yaml" "$@"
