#!/usr/bin/env bash
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
# Run against the local dev stack. --run makes real configured model calls.
set -euo pipefail

mode="${1:---prepare-only}"
case "$mode" in --prepare-only|--run) ;; *) echo 'Usage: managed-curl-test.sh [--prepare-only|--run]' >&2; exit 2 ;; esac
command -v curl >/dev/null
command -v jq >/dev/null
service_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${BASE:-http://127.0.0.1:18081}"
MODEL="${MODEL:-dashscope:qwen-plus}"
RUN_ID="$(date +%Y%m%d%H%M%S)-${RANDOM}"
output_root="${CURL_TEST_OUTPUT_ROOT:-$service_dir/target/curl-tests}"
mkdir -p "$output_root"
TEST_DIR="$(mktemp -d "$output_root/$RUN_ID.XXXXXX")"
umask 077

curl -fsS --max-time 30 "$BASE/actuator/health" | jq -e '.status == "UP"' >/dev/null
curl -fsS --max-time 30 "$BASE/api/auth/dev-session" | jq -e '.userId == "local-developer"' >/dev/null
curl -fsS --max-time 30 "$BASE/api/v1/me/scope" > "$TEST_DIR/scope.json"
TENANT="$(jq -er '.namespaces[0].tenant' "$TEST_DIR/scope.json")"
NAMESPACE="$(jq -er '.namespaces[0].name' "$TEST_DIR/scope.json")"
scope_headers=(-H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")

get() { curl -sS --fail-with-body --max-time 30 "${scope_headers[@]}" "$1"; }
post() {
    curl -sS --fail-with-body --max-time 30 "${scope_headers[@]}" -H 'Content-Type: application/json' \
        -H "Idempotency-Key: $3" -X POST "$1" --data-binary "@$2"
}
save_context() {
    local file="$TEST_DIR/case.env"
    : > "$file"
    for name in BASE MODEL RUN_ID TEST_DIR TENANT NAMESPACE ENV_ID AGENT_ID SESSION_ID SESSION_URL FILE_ID ARTIFACT_ID TURN_ID FORK_TURN_ID CHECKPOINT_ID TARGET_SESSION_ID TARGET_URL; do
        printf '%s=%q\n' "$name" "${!name:-}" >> "$file"
    done
    cp "$file" "$output_root/latest.env"
}
trap save_context EXIT

wait_ready() {
    local url="$1" state
    for ((i=0; i<120; i++)); do
        get "$url" > "$TEST_DIR/ready.json"
        state="$(jq -r '.state' "$TEST_DIR/ready.json")"
        [[ "$state" == ready ]] && return
        if [[ "$state" == failed ]]; then cat "$TEST_DIR/ready.json" >&2; return 1; fi
        sleep .5
    done
    echo "Session did not become ready: $url" >&2; return 1
}
wait_command() {
    local url="$1" wanted="$2" status
    for ((i=0; i<120; i++)); do
        get "$url" > "$TEST_DIR/command.json"
        status="$(jq -r '.command_status' "$TEST_DIR/command.json")"
        [[ "$status" == "$wanted" ]] && return
        if [[ "$status" == rejected || "$status" == failed ]]; then cat "$TEST_DIR/command.json" >&2; return 1; fi
        sleep .5
    done
    echo "Command did not settle: $url" >&2; cat "$TEST_DIR/command.json" >&2; return 1
}
wait_turn() {
    local url="$1" turn="$2" status
    for ((i=0; i<180; i++)); do
        get "$url/turns/$turn" > "$TEST_DIR/turn-current.json"
        status="$(jq -r '.status' "$TEST_DIR/turn-current.json")"
        case "$status" in
            completed) return ;;
            failed|interrupted|cancelled) cat "$TEST_DIR/turn-current.json" >&2; return 1 ;;
        esac
        # requires_action can be temporary while Hands completes an external tool.
        sleep 1
    done
    echo "Turn still pending; inspect $url/snapshot and $url/tool-tasks?turn=$turn" >&2
    cat "$TEST_DIR/turn-current.json" >&2; return 1
}
submit() {
    local url="$1" label="$2" message="$3"
    jq -n --arg message "$message" '{message:$message}' > "$TEST_DIR/$label-input.json"
    post "$url/turns" "$TEST_DIR/$label-input.json" "curl-$RUN_ID-$label" > "$TEST_DIR/$label-turn.json"
    TURN_ID="$(jq -er '.id' "$TEST_DIR/$label-turn.json")"
    save_context
}
show_messages() {
    get "$1/snapshot?limit=100" > "$TEST_DIR/snapshot.json"
    jq -r '.resources.items.data[]?.data.item | select(.role == "assistant") | .content[]? | select(.type == "text") | .text' "$TEST_DIR/snapshot.json"
}
export_all() {
    local url="$1" output="$2" after='' through='' next more count received
    : > "$output"
    for ((window=0; window<100; window++)); do
        curl -fsS --max-time 60 --max-filesize 67108864 "${scope_headers[@]}" -G "$url/export" \
            --data-urlencode "after=$after" --data-urlencode "through=$through" --data-urlencode 'limit=1000' \
            -D "$TEST_DIR/export-headers.txt" -o "$TEST_DIR/export-window.ndjson"
        header() { awk -F ': *' -v field="$1" 'tolower($1)==field {gsub("\r","",$2); print $2}' "$TEST_DIR/export-headers.txt"; }
        next="$(header x-next-cursor)"; more="$(header x-has-more)"; count="$(header x-export-count)"
        [[ -n "$next" && "$count" =~ ^[0-9]+$ && ( "$more" == true || "$more" == false ) ]]
        if [[ -z "$through" ]]; then through="$(header x-export-through)"; fi
        [[ -n "$through" && "$(header x-export-through)" == "$through" ]]
        jq -se --arg session "${url##*/}" 'all(.[]; .schema_version == 1 and .session_id == $session)' "$TEST_DIR/export-window.ndjson" >/dev/null
        received="$(wc -l < "$TEST_DIR/export-window.ndjson" | tr -d ' ')"
        [[ "$received" == "$count" ]]
        cat "$TEST_DIR/export-window.ndjson" >> "$output"
        if [[ "$more" == false ]]; then [[ "$next" == "$through" ]]; return; fi
        [[ "$count" != 0 && "$next" != "$after" && "$(tail -n 1 "$TEST_DIR/export-window.ndjson" | jq -r '.cursor')" == "$next" ]]
        after="$next"
    done
    echo 'Export exceeded 100 windows; partial file retained for inspection.' >&2; return 1
}

echo '1. Use the Environment already served by the local Hands Worker'
get "$BASE/api/environments" > "$TEST_DIR/environments.json"
ENV_ID="${ENV_ID:-$(jq -er '[.[] | select(.name == "Local Hands Docker" and .type == "self_hosted")][0].id // empty' "$TEST_DIR/environments.json")}"
get "$BASE/api/environments/$ENV_ID" | jq 'del(.apiKey)'

echo '2. Create a Managed Agent and Session'
jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" --arg key "curl-managed-$RUN_ID" --arg env "$ENV_ID" --arg model "$MODEL" '{
    tenant:$tenant,namespace:$namespace,agentKey:$key,displayName:$key,ownerType:"user",binding:{kind:"managed"},
    definition:{name:$key,system:"You are a helpful assistant. Remember conversation context and use the workspace tools when requested.",
        model:$model,maxIters:8,defaultEnvironmentId:$env,mcpServers:[],
        tools:[{type:"agent_toolset",defaultConfig:{enabled:true,permissionPolicy:{type:"always_allow"}}}]}}
' > "$TEST_DIR/agent-input.json"
post "$BASE/api/v1/agents" "$TEST_DIR/agent-input.json" "curl-$RUN_ID-agent" > "$TEST_DIR/agent.json"
AGENT_ID="$(jq -er '.agent.id' "$TEST_DIR/agent.json")"
get "$BASE/api/v1/agents/$AGENT_ID" > "$TEST_DIR/agent-read.json"
jq -n --arg agent "$AGENT_ID" '{target:{type:"agent",id:$agent}}' > "$TEST_DIR/session-input.json"
post "$BASE/api/v1/agent-sessions" "$TEST_DIR/session-input.json" "curl-$RUN_ID-session" > "$TEST_DIR/session.json"
SESSION_ID="$(jq -er '.id' "$TEST_DIR/session.json")"
SESSION_URL="$BASE/api/v1/agent-sessions/$SESSION_ID"
wait_ready "$SESSION_URL"
post "$BASE/api/v1/agent-sessions" "$TEST_DIR/session-input.json" "curl-$RUN_ID-session" | jq -e --arg id "$SESSION_ID" '.id == $id' >/dev/null
get "$SESSION_URL/capabilities" | jq .

echo '3. Save budget with its observed revision; verify a stale revision returns 412'
get "$SESSION_URL/budget" > "$TEST_DIR/budget-before.json"
revision="$(jq -er '.revision' "$TEST_DIR/budget-before.json")"
curl -fsS --max-time 30 "${scope_headers[@]}" -X PUT "$SESSION_URL/budget" -H 'Content-Type: application/json' \
    -H "If-Match: $revision" -d '{"max_model_calls":80,"max_total_tokens":200000}' > "$TEST_DIR/budget-saved.json"
status="$(curl -sS --max-time 30 "${scope_headers[@]}" -X PUT "$SESSION_URL/budget" -H 'Content-Type: application/json' \
    -H "If-Match: $revision" -d '{"max_model_calls":80}' -o "$TEST_DIR/budget-conflict.json" -w '%{http_code}')"
[[ "$status" == 412 ]]

echo '4. Upload, read back and publish a Session file as an artifact'
printf 'curl test file: %s\n' "$RUN_ID" > "$TEST_DIR/input.txt"
curl -fsS --max-time 30 "${scope_headers[@]}" -X POST "$SESSION_URL/files" -H 'Content-Type: text/plain' \
    -H 'X-File-Name: curl-input.txt' -H "Idempotency-Key: curl-$RUN_ID-file" --data-binary "@$TEST_DIR/input.txt" > "$TEST_DIR/file.json"
FILE_ID="$(jq -er '.file_id' "$TEST_DIR/file.json")"
get "$SESSION_URL/files/$FILE_ID/content" > "$TEST_DIR/file-downloaded.txt"
cmp "$TEST_DIR/input.txt" "$TEST_DIR/file-downloaded.txt"
jq -n --arg file "$FILE_ID" '{file_id:$file}' > "$TEST_DIR/artifact-input.json"
post "$SESSION_URL/artifacts" "$TEST_DIR/artifact-input.json" "curl-$RUN_ID-artifact" > "$TEST_DIR/artifact.json"
ARTIFACT_ID="$(jq -er '.artifact_id' "$TEST_DIR/artifact.json")"
get "$SESSION_URL/artifacts/$ARTIFACT_ID/content" > "$TEST_DIR/artifact-downloaded.txt"
cmp "$TEST_DIR/input.txt" "$TEST_DIR/artifact-downloaded.txt"
save_context
echo "Resource context saved: $TEST_DIR/case.env"
if [[ "$mode" == --prepare-only ]]; then
    echo 'Preparation passed. No model request sent. Run with --run for the remaining steps.'
    exit 0
fi

echo '5. Submit a real model Turn; sample SSE, disconnect, and continue polling'
submit "$SESSION_URL" basic "记住暗号 CURL-${RUN_ID}。这一轮只回复已记住，不要调用工具。"
stream_status=0
curl -fsS -N --max-time 8 "${scope_headers[@]}" "$SESSION_URL/events/stream" > "$TEST_DIR/events.sse" || stream_status=$?
[[ "$stream_status" == 0 || "$stream_status" == 28 ]]
wait_turn "$SESSION_URL" "$TURN_ID"
post "$SESSION_URL/turns" "$TEST_DIR/basic-input.json" "curl-$RUN_ID-basic" | jq -e --arg id "$TURN_ID" '.id == $id' >/dev/null
status="$(curl -sS --max-time 30 "${scope_headers[@]}" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: curl-$RUN_ID-basic" -X POST "$SESSION_URL/turns" -d '{"message":"different body"}' \
    -o "$TEST_DIR/turn-conflict.json" -w '%{http_code}')"
[[ "$status" == 409 ]]
show_messages "$SESSION_URL"

echo '6. Continue the same conversation'
submit "$SESSION_URL" continuity '上一轮我要求你记住的暗号是什么？只回复暗号。'
wait_turn "$SESSION_URL" "$TURN_ID"
show_messages "$SESSION_URL"

echo '7. Exercise Hands file tools'
submit "$SESSION_URL" tools "请实际调用 write_file 工具，在工作目录创建 curl-${RUN_ID}.txt，写入 HELLO-HANDS-${RUN_ID}，然后实际调用 read_file 工具读出内容。不要只描述操作。"
wait_turn "$SESSION_URL" "$TURN_ID"
get "$SESSION_URL/tool-tasks?turn=$TURN_ID&limit=100" > "$TEST_DIR/tool-tasks.json"
jq '.data[] | {task_id,tool_name,status,result_status}' "$TEST_DIR/tool-tasks.json"
jq -e '[.data[].tool_name] | index("write_file") != null and index("read_file") != null' "$TEST_DIR/tool-tasks.json" >/dev/null
show_messages "$SESSION_URL"

echo '8. Inject context without starting a Turn, then consume it with a new Turn'
jq -n --arg text "附加上下文：本轮测试的城市是杭州，编号是 ${RUN_ID}。" '{input:[{role:"user",content:[{type:"text",text:$text}]}]}' > "$TEST_DIR/inject-input.json"
post "$SESSION_URL/inputs/inject" "$TEST_DIR/inject-input.json" "curl-$RUN_ID-inject" > "$TEST_DIR/inject.json"
input_id="$(jq -er '.input_id' "$TEST_DIR/inject.json")"
submit "$SESSION_URL" injected '刚才附加上下文中的城市和编号分别是什么？'
wait_turn "$SESSION_URL" "$TURN_ID"
get "$SESSION_URL/inputs/$input_id" | jq .
show_messages "$SESSION_URL"

echo '9. Read usage, snapshot, events, and export all windows at one fixed watermark'
get "$SESSION_URL/usage" | jq .
get "$SESSION_URL/turns/$TURN_ID/usage" | jq .
get "$SESSION_URL/snapshot?limit=100" > "$TEST_DIR/final-snapshot.json"
get "$SESSION_URL/events?limit=100" > "$TEST_DIR/events-page.json"
get "$SESSION_URL/subagents" > "$TEST_DIR/subagents.json"
export_all "$SESSION_URL" "$TEST_DIR/public-events.ndjson"

echo '10. Find the latest checkpoint through fixed-watermark, possibly empty pages'
after=''; through=''; CHECKPOINT_ID=''
for ((page=0; page<100; page++)); do
    query=(--data-urlencode 'limit=100')
    [[ -z "$after" ]] || query+=(--data-urlencode "after=$after")
    [[ -z "$through" ]] || query+=(--data-urlencode "through=$through")
    curl -fsS --max-time 30 "${scope_headers[@]}" -G "$SESSION_URL/checkpoints" \
        "${query[@]}" > "$TEST_DIR/checkpoints.json"
    latest="$(jq -r '.data[-1].checkpoint_id // empty' "$TEST_DIR/checkpoints.json")"
    [[ -z "$latest" ]] || CHECKPOINT_ID="$latest"
    [[ -n "$through" ]] || through="$(jq -er '.as_of' "$TEST_DIR/checkpoints.json")"
    [[ "$(jq -er '.as_of' "$TEST_DIR/checkpoints.json")" == "$through" ]]
    [[ "$(jq -r '.has_more' "$TEST_DIR/checkpoints.json")" == true ]] || break
    next="$(jq -er '.next_cursor' "$TEST_DIR/checkpoints.json")"
    [[ "$next" != "$after" ]]; after="$next"
done
[[ -n "$CHECKPOINT_ID" && "$(jq -r '.has_more' "$TEST_DIR/checkpoints.json")" == false ]]

echo '11. Restore context, confirm the durable command, and run another Turn'
jq -n --arg checkpoint "$CHECKPOINT_ID" '{checkpoint_id:$checkpoint,reason:"curl restore test"}' > "$TEST_DIR/restore-input.json"
post "$SESSION_URL/checkpoints/restore" "$TEST_DIR/restore-input.json" "curl-$RUN_ID-restore" > "$TEST_DIR/restore.json"
command="$(jq -er '.command_id' "$TEST_DIR/restore.json")"
wait_command "$SESSION_URL/commands/$command" completed
submit "$SESSION_URL" restored '恢复后的上下文里，我最开始给你的暗号是什么？'
wait_turn "$SESSION_URL" "$TURN_ID"

echo '12. Fork into an empty Session created with the same frozen Agent'
post "$BASE/api/v1/agent-sessions" "$TEST_DIR/session-input.json" "curl-$RUN_ID-target" > "$TEST_DIR/target.json"
TARGET_SESSION_ID="$(jq -er '.id' "$TEST_DIR/target.json")"
TARGET_URL="$BASE/api/v1/agent-sessions/$TARGET_SESSION_ID"
wait_ready "$TARGET_URL"
jq -n --arg checkpoint "$CHECKPOINT_ID" --arg target "$TARGET_SESSION_ID" \
    '{checkpoint_id:$checkpoint,target_session_id:$target,reason:"curl fork test"}' > "$TEST_DIR/fork-input.json"
post "$SESSION_URL/fork" "$TEST_DIR/fork-input.json" "curl-$RUN_ID-fork" > "$TEST_DIR/fork.json"
command="$(jq -er '.command_id' "$TEST_DIR/fork.json")"
wait_command "$SESSION_URL/commands/$command" completed
get "$TARGET_URL/snapshot?limit=100" | jq -e '.usage.model_calls == 0' >/dev/null
get "$TARGET_URL/budget" | jq .
SOURCE_TURN_ID="$TURN_ID"
submit "$TARGET_URL" forked '从继承的上下文中找出最开始的暗号，只回复暗号。'
wait_turn "$TARGET_URL" "$TURN_ID"
FORK_TURN_ID="$TURN_ID"
TURN_ID="$SOURCE_TURN_ID"
show_messages "$TARGET_URL"

echo '13. Archive and reactivate the source Session'
printf '{}' > "$TEST_DIR/empty.json"
post "$SESSION_URL/archive" "$TEST_DIR/empty.json" "curl-$RUN_ID-archive" > "$TEST_DIR/archive.json"
command="$(jq -er '.command_id' "$TEST_DIR/archive.json")"
wait_command "$SESSION_URL/lifecycle-commands/$command" applied
post "$SESSION_URL/restore" "$TEST_DIR/empty.json" "curl-$RUN_ID-reactivate" > "$TEST_DIR/reactivate.json"
command="$(jq -er '.command_id' "$TEST_DIR/reactivate.json")"
wait_command "$SESSION_URL/lifecycle-commands/$command" applied
save_context
echo "Full curl sequence finished. Source and fork are retained. Results: $TEST_DIR"
echo "Source Console: $BASE/managed/sessions/$SESSION_ID?tab=execution"
