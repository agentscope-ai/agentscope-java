#!/usr/bin/env bash
set -euo pipefail
durable_root="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$durable_root"
durable_maven_args=()
if [[ -n "${DURABLE_MAVEN_REPOSITORY:-}" ]]; then
  durable_maven_args+=("-Dmaven.repo.local=$DURABLE_MAVEN_REPOSITORY")
fi
mvn -B -ntp -T1 "${durable_maven_args[@]}" -f agentscope-durable/pom.xml \
  -Pdurable-standalone -pl :agentscope-durable-example-queued-hands -am package \
  -DskipTests -Djacoco.skip=true
if [[ "${1:-}" == "--compile-only" ]]; then
  exit 0
fi
if [[ $# -lt 2 ]]; then
  echo "usage: $0 <worker|brain> <endpoint> [data-directory]" >&2
  exit 2
fi
durable_role="$1"
durable_endpoint="$2"
durable_data="${3:-durable-queued-hands-data/$durable_role}"
durable_jar="$durable_root/agentscope-durable/durable-examples/queued/queued-hands/target/agentscope-durable-example-queued-hands.jar"
java -jar "$durable_jar" \
  --durable.queued-hands.role="$durable_role" \
  --durable.queued-hands.endpoint="$durable_endpoint" \
  --durable.queued-hands.data-directory="$durable_data" \
  --durable.queued-hands.timeout=60s
