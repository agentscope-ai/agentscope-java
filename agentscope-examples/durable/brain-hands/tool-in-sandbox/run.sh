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
  printf 'usage: %s --compile-only | <brain|hands> <endpoint> [data-directory]\n' "$0" >&2
}

durable_compile_only=false
if [[ "${1:-}" == "--compile-only" ]]; then
  if [[ $# -ne 1 ]]; then
    usage
    exit 2
  fi
  durable_compile_only=true
else
  if [[ $# -lt 2 || $# -gt 3 || ( "$1" != "brain" && "$1" != "hands" ) ]]; then
    usage
    exit 2
  fi
  if [[ "$2" =~ ^[[:space:]]*$ || "$2" == --* || ( $# -eq 3 && "$3" =~ ^[[:space:]]*$ ) ]]; then
    usage
    exit 2
  fi
  durable_process="$1"
  durable_endpoint="$2"
  durable_data="${3:-durable-brain-hands-tool-in-sandbox-data/$durable_process}"
fi

durable_root="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$durable_root"
durable_maven_args=()
if [[ -n "${DURABLE_MAVEN_REPOSITORY:-}" ]]; then
  durable_maven_args+=("-Dmaven.repo.local=$DURABLE_MAVEN_REPOSITORY")
fi
mvn -B -ntp -T1 "${durable_maven_args[@]}" -f agentscope-durable/pom.xml \
  -Pdurable-standalone -pl :agentscope-durable-example-brain-hands-tool-in-sandbox -am package \
  -DskipTests -Djacoco.skip=true
if [[ "$durable_compile_only" == true ]]; then
  exit 0
fi

durable_jar="$durable_root/agentscope-examples/durable/brain-hands/tool-in-sandbox/target/agentscope-durable-example-brain-hands-tool-in-sandbox-$durable_process.jar"
exec java -jar "$durable_jar" \
  --durable.brain-hands-tool-in-sandbox.endpoint="$durable_endpoint" \
  --durable.brain-hands-tool-in-sandbox.data-directory="$durable_data" \
  --durable.brain-hands-tool-in-sandbox.timeout=60s
