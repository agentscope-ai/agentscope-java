#!/usr/bin/env bash
# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
# Current native Managed Session verification; snapshots/fork are not assumed capabilities.
set -euo pipefail
service_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:---prepare-only}"
case "$mode" in
    --prepare-only) extra=() ;;
    --run) extra=(--model-turn) ;;
    *) echo 'Usage: managed-curl-test.sh [--prepare-only|--run]' >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || { echo 'Expected one mode argument' >&2; exit 2; }
umask 077
output_root="${CURL_TEST_OUTPUT_DIR:-$service_dir/target/curl-tests}"
mkdir -p "$output_root"
run_dir="$(mktemp -d "$output_root/native-XXXXXXXX")"
export BUILDER_LOCAL_DEV="${BUILDER_LOCAL_DEV:-true}"
if [[ -n "${ENV_ID:-}" ]]; then extra+=(--environment-id "$ENV_ID"); fi
if [[ -n "${MODEL:-}" ]]; then extra+=(--model "$MODEL"); fi
if [[ -n "${SMOKE_ENV_FILE:-}" ]]; then extra+=(--env-file "$SMOKE_ENV_FILE"); fi
exec python3 "$service_dir/release/smoke.py" --base "${BASE:-http://localhost:18080}" \
    --state "$run_dir/state.json" "${extra[@]}"
