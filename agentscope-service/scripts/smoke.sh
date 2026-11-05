#!/usr/bin/env bash
# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
# Native Session API smoke; accepts the same options as release/smoke.py.
set -euo pipefail
service_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export BUILDER_LOCAL_DEV="${BUILDER_LOCAL_DEV:-true}"
exec python3 "$service_dir/release/smoke.py" \
    --base "${BASE:-http://localhost:18080}" \
    --state "${SMOKE_STATE:-$service_dir/.dev-stack/smoke-state.json}" "$@"
