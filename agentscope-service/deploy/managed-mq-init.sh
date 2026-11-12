#!/usr/bin/env sh
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
set -eu
: "${ROCKETMQ_HOME:?Set the RocketMQ 5.5.1 installation directory}"
: "${MANAGED_MQ_NAMESRV:?Set NameServer address}"
: "${MANAGED_MQ_CLUSTER:?Set Broker cluster name}"
queues=${MANAGED_MQ_QUEUES:-8}
expiration=${MANAGED_MQ_TOPIC_EXPIRATION_MINS:-20160}
for number in "$queues" "$expiration"; do
  case "$number" in ''|*[!0-9]*) echo 'Queue count and expiration must be positive integers' >&2; exit 2;; esac
  [ "$number" -gt 0 ] || { echo 'Queue count and expiration must be positive integers' >&2; exit 2; }
done
admin="$ROCKETMQ_HOME/bin/mqadmin"
admin_command() {
  case "$1" in
    updateTopic) expected='create topic to';;
    updateSubGroup) expected='create subscription group to';;
    *) printf '%s\n' 'Unsupported MQ initialization command' >&2; return 2;;
  esac
  attempt=1
  while [ "$attempt" -le 3 ]; do
    confirmed=false
    if response=$(sh "$admin" "$@" 2>&1); then
      case "$response" in
        *'SubCommandException:'*|*'[error]'*|*'Exception in thread'*) ;;
        *"$expected"*' success.'*) confirmed=true;;
      esac
    fi
    printf '%s\n' "$response"
    [ "$confirmed" != true ] || return 0
    [ "$attempt" -ge 3 ] || sleep 1
    attempt=$((attempt + 1))
  done
  printf '%s\n' 'MQ resource initialization was not confirmed' >&2
  return 1
}
admin_command updateTopic -n "$MANAGED_MQ_NAMESRV" -c "$MANAGED_MQ_CLUSTER" \
  -t DefaultHeartBeatSyncerTopic -r 1 -w 1
create_parent() {
  admin_command updateTopic -n "$MANAGED_MQ_NAMESRV" -c "$MANAGED_MQ_CLUSTER" -t "$1" \
    -r "$queues" -w "$queues" -a "+message.type=LITE,+lite.topic.expiration=$expiration"
  admin_command updateSubGroup -n "$MANAGED_MQ_NAMESRV" -c "$MANAGED_MQ_CLUSTER" -g "$2" \
    -o true -m true --attributes "+lite.bind.topic=$1,+lite.sub.model=Shared,+lite.sub.wildcard=true"
}
for channel in turns tools resumes executes controls projections; do
  case "$channel" in
    turns) topic=${MANAGED_AGENTS_RUNTIME_MQ_TURN_TOPIC:-ma_turns_v3}; group=${MANAGED_AGENTS_RUNTIME_MQ_HARNESS_RUNTIME_GROUP:-ma_brain_v3};;
    tools) topic=${MANAGED_AGENTS_RUNTIME_MQ_TOOL_EVENT_TOPIC:-ma_tool_events_v3}; group=${MANAGED_AGENTS_RUNTIME_MQ_TOOL_EVENTS_GROUP:-ma_brain_tool_events_v3};;
    executes) topic=${MANAGED_AGENTS_RUNTIME_MQ_TOOL_EXECUTE_TOPIC:-ma_tool_execute_v1}; group=${MANAGED_AGENTS_RUNTIME_MQ_TOOL_EXECUTE_GROUP:-ma_hands_execute_v1};;
    controls) topic=${MANAGED_AGENTS_RUNTIME_MQ_TOOL_CONTROL_TOPIC:-ma_tool_control_v1}; group=${MANAGED_AGENTS_RUNTIME_MQ_TOOL_CONTROL_GROUP:-ma_hands_control_v1};;
    projections) topic=${MANAGED_AGENTS_RUNTIME_MQ_PROJECTION_TOPIC:-ma_session_projections_v3}; group=${MANAGED_AGENTS_RUNTIME_MQ_PROJECTION_GROUP:-ma_session_projections_v3};;
    resumes) topic=${MANAGED_AGENTS_RUNTIME_MQ_RESUME_TOPIC:-ma_turn_resumes_v3}; group=${MANAGED_AGENTS_RUNTIME_MQ_RESUME_GROUP:-ma_brain_resumes_v3};;
  esac
  create_parent "$topic" "$group"

done

# Provision only when explicitly enabling the database Session queue.
if [ "${MANAGED_AGENTS_RUNTIME_SESSION_QUEUE_ENABLED:-false}" = true ]; then
  create_parent "${MANAGED_AGENTS_RUNTIME_MQ_SESSION_READY_TOPIC:-ma_session_ready_v1}" \
    "${MANAGED_AGENTS_RUNTIME_MQ_SESSION_READY_GROUP:-ma_session_ready_v1}"
fi
