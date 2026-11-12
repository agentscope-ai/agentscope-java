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
  -Pdurable-standalone -pl :agentscope-durable-example-sandbox-hands -am package \
  -DskipTests -Djacoco.skip=true
durable_jar="$durable_root/agentscope-durable/durable-examples/integrated/sandbox-hands/target/agentscope-durable-example-sandbox-hands.jar"
for phase in start resume; do
  java -jar "$durable_jar" \
    --durable.sandbox-hands.phase="$phase" \
    --durable.sandbox-hands.data-directory="$durable_tmp/data" \
    | tee "$durable_tmp/$phase.log"
done
grep -q \
  'phase=START .*turnStatus=WAITING executionState=RUNNING.*deleted=false modelInvocations=1' \
  "$durable_tmp/start.log"
grep -q \
  'phase=RESUME .*turnStatus=SUCCEEDED executionState=SUCCEEDED.*deleted=true modelInvocations=2 answer=Sandbox result applied: recovered' \
  "$durable_tmp/resume.log"
