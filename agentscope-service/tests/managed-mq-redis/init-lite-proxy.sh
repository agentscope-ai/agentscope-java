#!/bin/sh
# Local-fixture readiness: mqadmin can print an error and still return exit code zero.
set -eu
admin=/home/rocketmq/rocketmq-5.5.1/bin/mqadmin
attempt=0
registered=false
while [ "$attempt" -lt 12 ]; do
  registry=$(sh "$admin" clusterList -n namesrv:9876 2>&1) || registry=''
  case "$registry" in
    *ManagedLiteTest*managed-lite-broker-a*127.0.0.1:21911*) registered=true; break;;
  esac
  attempt=$((attempt + 1))
  sleep 1
done
if [ "$registered" != true ]; then
  printf '%s\n' 'Broker did not register with NameServer' >&2
  exit 1
fi
creation=$(sh "$admin" updateTopic -n namesrv:9876 -c ManagedLiteTest \
  -t DefaultHeartBeatSyncerTopic -r 1 -w 1 2>&1)
printf '%s\n' "$creation"
case "$creation" in
  *'SubCommandException:'*|*'[error]'*|*'Exception in thread'*)
    printf '%s\n' 'System topic creation was not confirmed' >&2; exit 1;;
  *'create topic to '*' success.'*) exit 0;;
  *) printf '%s\n' 'System topic creation was not confirmed' >&2; exit 1;;
esac
