#!/usr/bin/env bash
set -euo pipefail
durable_root="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$durable_root"
durable_maven_args=()
if [[ -n "${DURABLE_MAVEN_REPOSITORY:-}" ]]; then
  durable_maven_args+=("-Dmaven.repo.local=$DURABLE_MAVEN_REPOSITORY")
fi
mvn -B -ntp -T1 "${durable_maven_args[@]}" -f agentscope-durable/pom.xml \
  -Pdurable-standalone -pl :agentscope-durable-example-http-server -am package \
  -DskipTests -Djacoco.skip=true
durable_jar="$durable_root/agentscope-examples/durable/integrated/http-server/target/agentscope-durable-example-http-server.jar"
exec java -jar "$durable_jar" "$@"
