#!/usr/bin/env bash
# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
set -euo pipefail

usage() {
  printf 'usage: %s --compile-only | <worker|brain> <endpoint> [data-directory] [Spring arguments...]\n' "$0" >&2
}

durable_compile_only=false
if [[ "${1:-}" == "--compile-only" ]]; then
  durable_compile_only=true
  shift
  if [[ $# -ne 0 ]]; then
    usage
    exit 2
  fi
else
  if [[ $# -lt 2 || ( "$1" != "worker" && "$1" != "brain" ) ]]; then
    usage
    exit 2
  fi
  durable_role="$1"
  durable_endpoint="$2"
  shift 2
  durable_data="durable-queued-sandbox-hands-data/$durable_role"
  if [[ $# -gt 0 && "$1" != --* ]]; then
    durable_data="$1"
    shift
  fi
fi

durable_root="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$durable_root"
durable_maven_args=()
if [[ -n "${DURABLE_MAVEN_REPOSITORY:-}" ]]; then
  durable_maven_args+=("-Dmaven.repo.local=$DURABLE_MAVEN_REPOSITORY")
fi
mvn -B -ntp -T1 "${durable_maven_args[@]}" -f agentscope-durable/pom.xml \
  -Pdurable-standalone -pl :agentscope-durable-example-queued-sandbox-hands -am package \
  -DskipTests -Djacoco.skip=true
if [[ "$durable_compile_only" == true ]]; then
  exit 0
fi

durable_jar="$durable_root/agentscope-durable/durable-examples/queued/sandbox-hands/target/agentscope-durable-example-queued-sandbox-hands.jar"
exec java -jar "$durable_jar" \
  --durable.queued-sandbox-hands.role="$durable_role" \
  --durable.queued-sandbox-hands.endpoint="$durable_endpoint" \
  --durable.queued-sandbox-hands.data-directory="$durable_data" \
  --durable.queued-sandbox-hands.timeout=60s \
  "$@"
