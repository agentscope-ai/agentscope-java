#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
cd "$here/../../.."
lab=$(mktemp -d)
network="subscriber-$(basename "$lab" | tr '[:upper:]' '[:lower:]')"
cleanup() {
    docker rm -fv "$network-client" "$network-pg" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
    rm -rf "$lab"
}
trap cleanup EXIT
mvn -B -ntp -pl :agentscope-durable-example-echo-tool -am package -DskipTests -Dmaven.javadoc.skip=true -Djacoco.skip=true
jarfile=$(ls "$PWD"/agentscope-examples/durable/brain-hands/echo-tool/target/*-brain.jar)
version=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("agentscope-dependencies-bom/pom.xml").findtext("{*}properties/{*}postgresql.version"))')
mvn -B -ntp -f agentscope-dependencies-bom/pom.xml dependency:copy "-Dartifact=org.postgresql:postgresql:$version" "-DoutputDirectory=$lab"
cd "$lab"
jar xf "$jarfile" BOOT-INF
cp=".:postgresql-$version.jar:BOOT-INF/classes:BOOT-INF/lib/*"
javac --release 17 -cp "$cp" -d . "$here"/*.java
docker network create "$network" >/dev/null
docker run --rm -d --name "$network-pg" --network "$network" --network-alias pg -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine >/dev/null
docker run --rm -d --name "$network-client" --network "$network" -v "$lab:/lab:ro" -w /lab eclipse-temurin:21-jre sleep infinity >/dev/null
client() { docker exec "$network-client" "$@"; }
probe() { local cls=$1; shift; client java -Xmx256m -cp "$cp" "io.agentscope.durable.examples.brainhands.echo.$cls" "$@"; }
for ((i=0;i<60;i++)); do docker exec "$network-pg" pg_isready -U postgres && break; sleep 1; done
probe SubscriberHost init
probe LongHistory
probe SubscriberHost brain >"$lab/brain.log" 2>&1 &
for ((i=0;i<60;i++)); do grep -qx READY "$lab/brain.log" && break; sleep 1; done
grep -qx READY "$lab/brain.log" || { cat "$lab/brain.log"; exit 1; }
probe Subscriber save
turn=$(probe SubscriberHost crash | awk '/^TURN /{print $2}')
status=0
probe Subscriber watch "$turn" || status=$?
test "$status" = 73
probe SubscriberHost offline
probe SubscriberHost prune
client touch /tmp/hold-reset
probe Subscriber watch "$turn" &
watcher=$!
for ((i=0;i<60;i++)); do client test -e /tmp/reset-reached && break; sleep 1; done
client test -e /tmp/reset-reached
probe SubscriberHost during-reset
client rm /tmp/hold-reset
wait "$watcher"
probe Subscriber pressure "$turn"
