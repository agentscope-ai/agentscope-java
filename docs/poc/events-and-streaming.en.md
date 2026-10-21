# POC Part 2: Events and streaming

[中文](events-and-streaming.md) · [POC reading order](README.en.md) · Previous: [API quickstart](api-quickstart.en.md) · Next: [Tool calls and in-flight interaction](tool-collaboration.en.md)

This guide follows the event flow in the [Claude Session event stream guide](https://platform.claude.com/docs/en/managed-agents/events-and-streaming#send-events), using the same **cURL + jq, two-terminal** approach as the API quickstart: open a stream, send user.message, read the answer, continue or redirect work.

Continue after the quickstart’s second exchange, reusing its Agent, Session, shared variables and `api` helper. Requests use only Session retrieval and Events send/list/stream.

## 1. Reuse the quickstart context

Complete [Part 1](api-quickstart.en.md) and wait for the last answer and idle. The following commands use its existing `BASE_URL`, `TENANT`, `NAMESPACE`, `AGENT_ID`, `ENVIRONMENT_ID`, `SESSION_ID`, `RUN_ID`, `D` and `api` without configuring them again.

Terminal B keeps the client.sh already sourced in the quickstart. For a new terminal, run the complete `source /actual/path/client.sh` command printed in quickstart step 3 to restore the same context.

If terminal A is still observing the quickstart stream, press Ctrl+C to stop observation before the query below. Ctrl+C does not cancel the Session’s work.

```bash
mkdir -p "$D/events"
until api "/v1/sessions/$SESSION_ID" | jq -e '.status == "idle"' >/dev/null; do
  sleep 1
done
api "/v1/sessions/$SESSION_ID" | jq '{id, status, agent}'
```

Run this wait after receiving the quickstart’s final answer and idle event. It confirms that the queried Session state is also idle before another message is sent. This part stores files in `$D/events/` and uses `$RUN_ID-events-...` for idempotency keys, keeping its requests and responses separate from the quickstart. Retain the original body and key when retrying.

## 2. Open the stream first (terminal A)

```bash
api "/v1/sessions/$SESSION_ID/events/stream" -N | tee "$D/events/events.sse"
```

Wait for the `:connected` comment before sending. It confirms this implementation has captured the stream boundary. Leave terminal A open; tee also saves the observed raw SSE to `$D/events/events.sse`.

The stream observes future processed events without replaying history. Frames normally contain `event: <type>` and JSON `data: ...`; heartbeats are comments. Raw SSE is not a JSON document to pipe directly to jq. Use the history commands below to extract text with jq.

## 3. Send user.message (terminal B)

In terminal B, using the context from the quickstart:

```bash
cat > "$D/events/message-request.json" <<'JSON'
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Explain what this Agent can help me with."}]
  }]
}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-events-message-1" \
  --data-binary "@$D/events/message-request.json" > "$D/events/message.json"
jq . "$D/events/message.json"
EVENT_ID=$(jq -er '.data[0].id' "$D/events/message.json")
printf 'Accepted Event: %s\n' "$EVENT_ID"
```

HTTP 200 and an Event ID acknowledge durable acceptance. A null processed_at means processing has not finished. This is an ordinary text event without a agentscope_service intent; ordinary messages require an idle Session with no active/queued work.

For an uncertain HTTP response, retry this cURL write with the same JSON file and `$RUN_ID-events-message-1`. Do not regenerate RUN_ID or change the key. A frozen receipt does not update with execution or replay old answers into a new stream; observe the events for current progress.

Limits are 1–50 Events and 1 MiB per request, but ordinary batches support one message, one interrupt or ordered `[interrupt, message]`, not arbitrary batches of ordinary messages.

## 4. Observe the answer and idle boundary (terminal A)

| Event | Handling |
| --- | --- |
| user.message | The input is processed under its accepted Event ID; processed_at updates |
| agent.message | Render type=text blocks from content |
| agent.tool_use / agent.mcp_tool_use | Display actual tool name/input |
| agent.tool_result / agent.mcp_tool_result | Correlate by tool_use_id / mcp_tool_use_id |
| span.model_request_start / span.model_request_end | Model boundaries/usage when measurements exist |
| session.status_running | Work is executing |
| session.error | Inspect the error; later idle alone does not establish success |
| session.status_idle | Inspect stop_reason to understand why the interaction stopped |

`stop_reason.type:end_turn` means work stopped; both normal completion and interruption can produce it. Assistant messages contain committed text, not token deltas in this release. The SSE connection stays open after idle; keep it for the next message.

The `set +e` setting from the quickstart and shared script preserves the interactive shell and shared context when Ctrl+C is pressed. Ctrl+C stops observation without cancelling work. A GET that still says idle immediately after submission does not prove the new message has finished; follow its processing and subsequent status events.

## 5. Read history and answers (terminal B)

```bash
api "/v1/sessions/$SESSION_ID/events" -G \
  --data-urlencode 'limit=100' \
  --data-urlencode 'order=asc' > "$D/events/history-page.json"
jq . "$D/events/history-page.json"
jq -r '.data[] | select(.type == "agent.message") | .content[]? |
       select(.type == "text") | .text' "$D/events/history-page.json"
```

This prints the current page's assistant text, potentially including intermediate answers; it is not a separate final-output collection. History also preserves tool calls, results and state associations for UI reconstruction.

A non-null next_page means more pages. To traverse from the beginning and save JSONL:

```bash
PAGE=""
: > "$D/events/history.jsonl"
while true; do
  query=(-G --data-urlencode 'limit=100' --data-urlencode 'order=asc')
  if [[ -n "$PAGE" ]]; then
    query+=(--data-urlencode "page=$PAGE")
  fi
  api "/v1/sessions/$SESSION_ID/events" "${query[@]}" > "$D/events/history-page.json"
  jq -c '.data[]' "$D/events/history-page.json" >> "$D/events/history.jsonl"
  PAGE=$(jq -r '.next_page // empty' "$D/events/history-page.json")
  if [[ -z "$PAGE" ]]; then break; fi
done
jq -s '.' "$D/events/history.jsonl"
```

Keep the filters and order unchanged, using page/next_page rather than another endpoint's cursor. Each traversal fixes its upper boundary and projected versions; start a fresh query for subsequent changes.

Filter event types:

```bash
api "/v1/sessions/$SESSION_ID/events" -G \
  --data-urlencode 'types[]=agent.message' \
  --data-urlencode 'types[]=session.error' \
  --data-urlencode 'types[]=session.status_idle' \
  --data-urlencode 'limit=100' \
  --data-urlencode 'order=asc' | jq .
```

Repeat types[] using --data-urlencode to build query parameters instead of concatenating the URL manually. Filtered results also require next_page traversal; do not reuse an unfiltered query's cursor. The created_at[...] time filters compare against processed_at. See the reference for other fields.

## 6. Continue the idle Session (terminal B)

Keep terminal A's stream, or reopen it before writing. After the previous answer and idle event, run the wait and query from step 1 again to confirm the queried Session state is idle. Then reuse the Session with a new request key:

```bash
cat > "$D/events/follow-up-request.json" <<'JSON'
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Summarize the last answer in three points."}]
  }]
}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-events-message-2" \
  --data-binary "@$D/events/follow-up-request.json" > "$D/events/follow-up.json"
jq . "$D/events/follow-up.json"
```

Observe the new agent.message and idle in terminal A or refresh history. No new Session or resume endpoint is needed; the existing Session retains conversational context.

## 7. Interrupt and redirect (terminal B)

These are independent scenarios for active work, with the observer in terminal A left open. A simple text answer can finish quickly; an idle interrupt may have nothing to stop.

To request interruption alone:

```bash
cat > "$D/events/interrupt-request.json" <<'JSON'
{"events": [{"type": "user.interrupt"}]}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-events-interrupt" \
  --data-binary "@$D/events/interrupt-request.json" > "$D/events/interrupt.json"
jq . "$D/events/interrupt.json"
```

Acceptance does not mean a physical tool stopped immediately. Continue observing its outcome. For the **alternative scenario** of interrupting with a new direction, send interrupt and message as one ordered batch:

```bash
cat > "$D/events/redirect-request.json" <<'JSON'
{
  "events": [
    {"type": "user.interrupt"},
    {
      "type": "user.message",
      "content": [{"type": "text", "text": "Change direction: focus only on the risks."}]
    }
  ]
}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-events-redirect" \
  --data-binary "@$D/events/redirect-request.json" > "$D/events/redirect.json"
jq . "$D/events/redirect.json"
REPLACEMENT_EVENT_ID=$(jq -er '.data[1].id' "$D/events/redirect.json")
printf 'Replacement message Event: %s\n' "$REPLACEMENT_EVENT_ID"
```

The old work may emit idle before the replacement message starts. Do not treat that first idle as replacement completion: find the processed user.message whose id is REPLACEMENT_EVENT_ID in terminal A, then follow its answer and later idle. Use history if disconnected.

Keep real tool-use/result associations. Interruption does not authorize replaying an unknown external outcome.

## 8. Reconnect and fill in history

1. Stop the old observer in terminal A and run the streaming cURL command from step 2 again.
2. After the new :connected comment, keep the stream open while terminal B runs the full history traversal from step 5.
3. Rebuild the view from live events and history without treating historical idle as completion of a new message.

There is no Last-Event-ID, after resume or separate durable cursor. The tee in step 2 replaces the current observation log; history remains available through the API.

An Event ID can update from pending to processed. Dropping every repeated ID loses this transition. Application integrations should merge public representations by ID, allow processed_at updates and skip only identical representations. These cURL commands support manual observation/querying; they do not implement an automatic merge script.

## Scope relative to upstream features

| Upstream flow | This release |
| --- | --- |
| Ordinary text, interrupt, SSE, history | Use the commands above |
| requires_action, user.tool_confirmation | Client tool confirmation is not enabled |
| user.custom_tool_result / user.tool_result | Client external-result submission is not enabled |
| budget_reached / Budget | Budget pause/resume APIs are not enabled |
| system.message | Rejected by current validation; configure system instructions on the Agent |
| Threads/Workflow, preview/deltas | Outside this Session compatibility subset |

Server tools execute in Hands, without the client-confirmation/external-result flow. Sessions freeze their Agent definition; configure a new version and create another Session when system instructions change. Platform Turn/Input and independent final output remain optional extensions; see [API layers](api-reference.en.md#api-layers).

Actual contracts come from [Session validation/SSE](../../agentscope-service/service-session-portal/src/main/java/io/agentscope/builder/portal/session/ManagedSessionTurnsApi.java), [durable projection](../../agentscope-service/service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedSessionProtocol.java) and the [entry filter](../../agentscope-service/service-session-portal/src/main/java/io/agentscope/builder/portal/config/SessionApiErrors.java). This guide uses implemented upstream interaction patterns rather than claiming every upstream feature is supported.

Next: [Part 3: Tool calls and in-flight interaction](tool-collaboration.en.md). Reuse configuration to test real MCP calls, Brain/Hands, input, queuing, interruption and redirection.
