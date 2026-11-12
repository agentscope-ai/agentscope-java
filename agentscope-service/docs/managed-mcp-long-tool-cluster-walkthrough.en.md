# HTTP MCP long-tool and new Session protocol verification

[中文](managed-mcp-long-tool-cluster-walkthrough.md)

This guide uses Draft 3. Check the revision below before submitting work.
The [Draft 3 audit](../tests/session-protocol/draft3-audit.md) describes the review and upgrade;
actual publication, image digests and cluster acceptance evidence are stored in
`release/dist/deploy-draft3-audit/`. The earlier `release/dist/deploy-agencore-ma/mcp-long-tool/acceptance/report.json` remains historical evidence.

## Prepare

The isolated Kubernetes namespace is agencore-ma; the image registry namespace is agentcore.
The service is managed-agents-long-tool-mcp and the tool is longwait__long_wait. It performs a real wait,
without Sandbox, Environment Worker or NAS.

After upgrading:

```bash
bash managed-agents/scripts/cluster-api-shell.sh --long-tool
api "/v1/sessions/$SESSION_ID/capabilities" | jq '{protocol_revision,extensions,features}'
```

Continue only when protocol_revision is managed-agents-2026-10-19-draft3. The shell selects the manual
Session. Automated acceptance must use another Session, preserving the prepared empty manual one.

## Submit T1 and T2

```bash
submit_turn() {
  local key="$1" text="$2"
  jq -n --arg text "$text" '{events:[{type:"user.message",content:[{type:"text",text:$text}],managed_agents:{intent:"new_turn"}}]}' \
    | api "/v1/sessions/$SESSION_ID/events" -X POST -H "Idempotency-Key: $key" --data-binary @-
}
submit_turn "$RUN_ID-t1" "Call longwait__long_wait exactly once with marker=$RUN_ID and seconds=90. Wait for its real result and report marker, call_id, started_at and finished_at. Do not fabricate or repeat the call." > "$D/t1.json"
TURN1=$(jq -er '.data[0].managed_agents.turn_id' "$D/t1.json")
submit_turn "$RUN_ID-t2" "This is queued T2. Call no tools; report call_id and finished_at from the previous real result." > "$D/t2.json"
TURN2=$(jq -er '.data[0].managed_agents.turn_id' "$D/t2.json")
save_state
api "/v1/sessions/$SESSION_ID/turns/$TURN1" | jq .
api "/v1/sessions/$SESSION_ID/turns/$TURN2" | jq .
```

T1 progresses through accepted, preparing/running, waiting_tool, running and completed. T2 stays
accepted until T1 settles. HTTP 200 means durable admission. Retry an uncertain response with the
original body and key.

## Inject while suspended

While T1 is waiting_tool:

```bash
jq -n --arg turn "$TURN1" '{events:[{type:"user.message",content:[{type:"text",text:"Also state that you continued only after the tool really finished."}],managed_agents:{intent:"inject",turn_id:$turn}}]}' \
  | api "/v1/sessions/$SESSION_ID/events" -X POST -H "Idempotency-Key: $RUN_ID-inject" --data-binary @- > "$D/input.json"
INPUT=$(jq -er '.data[0].managed_agents.input_id' "$D/input.json")
api "/v1/sessions/$SESSION_ID/turns/$TURN1/inputs/$INPUT" | jq .
```

Input is accepted first and applied after the real tool result and Resume. It neither starts another
Turn nor cancels the tool. A new key against closed inputs gets 409; replaying an admitted key keeps
its frozen receipt.

## Observe and retrieve output

```bash
api "/v1/sessions/$SESSION_ID/events/stream" -N --max-time 180
api "/v1/sessions/$SESSION_ID/events?limit=100&order=asc" | jq .
api "/v1/sessions/$SESSION_ID/turns/$TURN1/output?limit=100" | jq .
api "/v1/sessions/$SESSION_ID/turns/$TURN2/output?limit=100" | jq .
```

The live stream starts on connection. Disconnection does not cancel work; Last-Event-ID resume is
unsupported. Reconnect by opening/buffering a new stream, reading history, then deduplicating Event IDs.
History uses page/next_page; output uses after/next_cursor. Output needs no SSE or snapshot. Compare
the real call_id/times in both outputs and confirm exactly one physical call in the tool logs.
Tool events are agent.mcp_tool_use/result; Turn/Input notifications are managed_agents.*.

The automated verifier is `managed-agents/tests/http-mcp-long-tool/verify_session.py`. It checks the
protocol revision before submitting work, then validates output and physical logs. The baseline and
Pod fault cases ran against the two-replica cluster on 2026-10-19; see [recovery acceptance](../tests/http-mcp-long-tool/dual-replica-recovery.en.md).
The initial test exposed lost in-flight results after Worker failure. After adding the recovery
contract, [follow-up acceptance](../tests/http-mcp-long-tool/recovery-v1-acceptance.en.md) passed Worker/Runtime restart,
original-result lookup, Suspend/Resume and T2 ordering.
