#!/usr/bin/env bash
set -euo pipefail
durable_root="$(cd "$(dirname "$0")/../../../.." && pwd)"
durable_tmp="$(mktemp -d)"
trap 'rm -rf "$durable_tmp"' EXIT
cd "$durable_root"
durable_maven_args=()
if [[ -n "${DURABLE_MAVEN_REPOSITORY:-}" ]]; then
  durable_maven_args+=("-Dmaven.repo.local=$DURABLE_MAVEN_REPOSITORY")
fi
mvn -B -ntp -T1 "${durable_maven_args[@]}" -f agentscope-durable/pom.xml \
  -Pdurable-standalone -pl :agentscope-durable-example-quickstart -am package \
  -DskipTests -Djacoco.skip=true
durable_jar="$durable_root/agentscope-examples/durable/integrated/quickstart/target/agentscope-durable-example-quickstart.jar"
for invocation in 1 2; do
  java -jar "$durable_jar" \
    --durable.quickstart.data-directory="$durable_tmp/backend" \
    > "$durable_tmp/run-$invocation.log"
  cat "$durable_tmp/run-$invocation.log"
done
grep -q 'status=SUCCEEDED.*modelInvocations=1' "$durable_tmp/run-1.log"
grep -q 'status=SUCCEEDED.*modelInvocations=0' "$durable_tmp/run-2.log"
first_turn="$(sed -n 's/.*turn=\([^ ]*\).*/\1/p' "$durable_tmp/run-1.log")"
second_turn="$(sed -n 's/.*turn=\([^ ]*\).*/\1/p' "$durable_tmp/run-2.log")"
test -n "$first_turn"
test "$first_turn" = "$second_turn"
echo 'Same Turn completed across two Spring Boot processes; model invoked once.'
