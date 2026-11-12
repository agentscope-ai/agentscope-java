#!/bin/sh
set -eu
admin=/home/rocketmq/rocketmq-5.5.1/bin/mqadmin
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
admin_command updateTopic \
  -n namesrv:9876 -c ManagedLiteTest -t DefaultHeartBeatSyncerTopic -r 1 -w 1
admin_command updateTopic \
  -n namesrv:9876 -c ManagedLiteTest -t ma_lite_turns_it_v2 -r 1 -w 1 -a '+message.type=LITE,+lite.topic.expiration=20160'
admin_command updateSubGroup \
  -n namesrv:9876 -c ManagedLiteTest -g ma_lite_brain_it_v2 -o true -m true --attributes '+lite.bind.topic=ma_lite_turns_it_v2,+lite.sub.model=Shared,+lite.sub.wildcard=true'
create_parent() {
  admin_command updateTopic \
    -n namesrv:9876 -c ManagedLiteTest -t "$1" -r 1 -w 1 \
    -a '+message.type=LITE,+lite.topic.expiration=20160'
  admin_command updateSubGroup \
    -n namesrv:9876 -c ManagedLiteTest -g "$2" -o true -m true \
    --attributes "+lite.bind.topic=$1,+lite.sub.model=Shared,+lite.sub.wildcard=true"
}
for prefix in ma_push_bus; do
  for channel in turns tools resumes; do
    topic="${prefix}_${channel}_it_v3"
    group="${prefix}_brain_it_v3"
    [ "$channel" != tools ] || group="${prefix}_tools_it_v3"
    [ "$channel" != resumes ] || group="${prefix}_resumes_it_v3"
    create_parent "$topic" "$group"
  done
done
create_parent ma_turns_v3 ma_brain_v3
create_parent ma_tool_events_v3 ma_brain_tool_events_v3
create_parent ma_turn_resumes_v3 ma_brain_resumes_v3

create_parent ma_tool_execute_v1 ma_hands_execute_v1
create_parent ma_tool_control_v1 ma_hands_control_v1

create_parent ma_session_ready_v1 ma_session_ready_v1

# Agent Service asynchronous projection consumer.
create_parent ma_session_projections_v3 ma_session_projections_v3
