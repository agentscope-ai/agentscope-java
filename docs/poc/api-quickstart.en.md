# POC Part 1: API quickstart

[中文](api-quickstart.md) · [POC reading order](README.en.md) · Next: [Events and streaming](events-and-streaming.en.md) · [API reference](api-reference.en.md)

Start this continuous walkthrough here. This page initializes the variables and `api` helper shared by the later parts.

Create an Agent, create a Session, open its event stream, send a plain `user.message`, and read `agent.message` until `session.status_idle`. This follows the Session/Events pattern in the [Claude Managed Agents quickstart](https://platform.claude.com/docs/en/managed-agents/quickstart) and [event guide](https://platform.claude.com/docs/en/managed-agents/events-and-streaming), within the subset implemented here.

The conversation uses only these Session endpoints:

```text
POST /v1/sessions
GET  /v1/sessions/{session_id}
GET  /v1/sessions/{session_id}/events/stream
POST /v1/sessions/{session_id}/events
GET  /v1/sessions/{session_id}/events
```

Agent/Environment provisioning remains this project's control-plane API. `POST /api/v1/agents` below is a platform setup step; it is not a Claude-compatible `/v1/agents` endpoint. Turn/Input queries, final-output queries, `agentscope_service` intents and capability discovery are optional platform extensions documented in the [reference](api-reference.en.md#api-layers).

## Before you start

Use a current deployment with schema migrations applied, Runtime and Hands running, and a valid server-side model configuration. Follow the [deployment guide](../../agentscope-service/deploy/README.md) to prepare PostgreSQL, Redis and MQ. This example disables tools and does not request files or Sandbox operations.

The POC cluster at `http://192.0.2.10` uses development identity mode with the shared `local-developer` identity. No login or token is required. The setup below selects the cluster’s text-verification Environment. Follow the steps in order, or override `ENVIRONMENT_ID` to select another environment.

Bash and zsh are supported. You need `curl` with `--fail-with-body` and `jq`. Keep the IDs from this dedicated Session and avoid concurrent writers while following the example.

## 1. Prepare the API helper (terminal A)

Run the setup blocks in one Bash or zsh shell. Responses are saved outside the repository. Initialize `BASE_URL`, `TENANT`, `NAMESPACE`, `ENVIRONMENT_ID`, `RUN_ID`, `D` and `api` only here; subsequent parts reuse them.

```bash
set +e
set -uo pipefail
export BASE_URL="${BASE_URL:-http://192.0.2.10}"
export TENANT="${TENANT:-default}"
export NAMESPACE="${NAMESPACE:-default}"
export ENVIRONMENT_ID="${ENVIRONMENT_ID:-env_edbcd65c892f}"
export RUN_ID="quickstart-$(date +%s)-$$"
export D=$(mktemp -d "${TMPDIR:-/tmp}/agentscope-service-api.XXXXXX")

api() {
  local request_path="$1"
  shift
  curl --fail-with-body -sS "$BASE_URL$request_path" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' \
    -H 'anthropic-version: 2023-06-01' \
    -H 'anthropic-beta: managed-agents-2026-04-01' \
    "$@"
}

api /api/v1/version | jq .
```

`request_path` is an ordinary local variable. In zsh, `path` is tied to `PATH`, so do not rename this variable to `path`. The examples contain no standalone `#` comment lines and can be pasted into default interactive zsh. The setup explicitly disables errexit while keeping unset-variable and pipeline-failure checks, so a failed request or interrupted stream does not discard the interactive context.

If you already ran the earlier example, redefine only the `api() { ... }` helper above, then run “Save the current context” in step 3. Saving automatically reloads the script in the current terminal; source it again in any other open terminals too. Keep the existing `D`, `RUN_ID`, `AGENT_ID` and `SESSION_ID`; no resources need to be recreated.

## 2. Create an Agent (platform setup)

Managed binding plus definition creates a runnable Agent. `ENVIRONMENT_ID` uses the cluster environment selected above. The model comes from deployment configuration and tools are explicitly disabled.

```bash
jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
  --arg key "$RUN_ID" --arg env "$ENVIRONMENT_ID" '
{
  tenant: $tenant,
  namespace: $namespace,
  agentKey: $key,
  displayName: "Quickstart Assistant",
  description: "A small conversational Agent for API integration",
  binding: {kind: "managed"},
  definition: {
    name: "Quickstart Assistant",
    system: "You are a helpful assistant. Answer clearly and remember this conversation.",
    maxIters: 8,
    tools: [{type: "agent_toolset", defaultConfig: {enabled: false}}]
  }
}
| if $env != "" then .definition.defaultEnvironmentId = $env else . end
' > "$D/agent-request.json"

api /api/v1/agents -X POST --data-binary "@$D/agent-request.json" \
  > "$D/agent.json"
export AGENT_ID=$(jq -er '.agent.id' "$D/agent.json")
printf 'Agent: %s\n' "$AGENT_ID"
```

Read the management ID from `agent.id`; Session responses return the resource directly.

## 3. Create a Session

Keep its ID for this conversation. Read its initial `idle` state, then save the parameters/helper for terminal B.

```bash
jq -n --arg agent "$AGENT_ID" --arg env "$ENVIRONMENT_ID" '
{
  agent: $agent,
  title: "My first API conversation",
  metadata: {source: "api-quickstart"}
}
| if $env != "" then .environment_id = $env else . end
' > "$D/session-request.json"

api /v1/sessions -X POST \
  -H "Idempotency-Key: $RUN_ID-session" \
  --data-binary "@$D/session-request.json" > "$D/session.json"
export SESSION_ID=$(jq -er '.id' "$D/session.json")
printf 'Session: %s\n' "$SESSION_ID"

api "/v1/sessions/$SESSION_ID" | jq '{id, status, agent}'
```

### Save the current context

This block only writes a local `client.sh`. It can be rerun without creating another Agent or Session. The saved script can be sourced in Bash and zsh.

```bash
{
  printf '%s\n' 'set +e' 'set -uo pipefail'
  printf 'export BASE_URL=%q\n' "$BASE_URL"
  printf 'export TENANT=%q\n' "$TENANT"
  printf 'export NAMESPACE=%q\n' "$NAMESPACE"
  printf 'export ENVIRONMENT_ID=%q\n' "$ENVIRONMENT_ID"
  printf 'export AGENT_ID=%q\n' "$AGENT_ID"
  printf 'export SESSION_ID=%q\n' "$SESSION_ID"
  printf 'export RUN_ID=%q\n' "$RUN_ID"
  printf 'export D=%q\n' "$D"
  typeset -f api
} > "$D/client.sh"
source "$D/client.sh"
printf 'In terminal B, run: source %q\n' "$D/client.sh"
```

## 4. Open the event stream first (terminal A)

```bash
api "/v1/sessions/$SESSION_ID/events/stream" -N
```

Wait for the `:connected` comment before sending. That frame confirms this implementation has captured the stream boundary. Keep terminal A open. The initial `set +e` lets Ctrl+C stop observation without errexit closing the interactive shell. If a network error closes the stream, inspect the failure and reconnect as described below.

## 5. Send user.message (terminal B)

Open another Bash or zsh terminal and run the exact `source .../client.sh` command printed in step 3. Then send:

```bash
cat > "$D/message-request.json" <<'JSON'
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "My name is Lin. Introduce yourself in one sentence."}]
  }]
}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-message-1" \
  --data-binary "@$D/message-request.json" > "$D/message.json"
jq . "$D/message.json"
EVENT_ID=$(jq -er '.data[0].id' "$D/message.json")
printf 'Accepted Event: %s\n' "$EVENT_ID"
```

The JSON is an ordinary user event, without a platform intent or output-selection header. HTTP 200 and an Event ID acknowledge acceptance. `processed_at:null` means it has not finished processing. Retry an uncertain HTTP result with this same body and key; a new key creates new work.

In terminal A, handle the event types:

| Event | What to do |
| --- | --- |
| `user.message` | The accepted input is now processed under the same Event ID |
| `agent.message` | Render text blocks in `content` |
| `agent.tool_use` / `agent.mcp_tool_use` | Display tool activity when tools are configured |
| `session.error` | Inspect the error; idle alone does not establish successful execution |
| `session.status_idle` with `stop_reason.type:end_turn` | This interaction has stopped; send another message when ready |

For this tool-free example, expect an assistant message followed by idle. Assistant messages arrive after the model request produces committed text, not token deltas. The SSE connection remains open after idle. Ctrl+C stops observation without cancelling the Session's work.

## 6. Read event history

After the interaction is idle, run in terminal B:

```bash
api "/v1/sessions/$SESSION_ID/events" -G \
  --data-urlencode 'limit=100' --data-urlencode 'order=asc' \
  > "$D/history.json"
jq . "$D/history.json"
jq -r '.data[] | select(.type == "agent.message") | .content[]? |
       select(.type == "text") | .text' "$D/history.json"
```

This prints all assistant messages on the current page, not a separate final-output collection. Follow `next_page` by sending it as `page` until null, retaining the same filters/order. Each traversal freezes its upper boundary; start a new traversal to see later processing-state updates. The SDK example below follows pages automatically.

The stream contains future events only. After a disconnect, open a new stream and buffer it, fetch history, then merge by Event ID while allowing `processed_at` to update. No `Last-Event-ID`/`after` resume is implemented. A quick initial `GET Session` that still says idle is not proof that an accepted message has finished; follow its events instead.

## 7. Continue the same conversation

Keep or reopen the stream before this write, and wait until the previous interaction is idle:

```bash
jq -n '{events: [{
  type: "user.message",
  content: [{type: "text", text: "What name did I tell you?"}]
}]}' > "$D/message-2-request.json"

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-message-2" \
  --data-binary "@$D/message-2-request.json" | jq .
```

Read the answer in terminal A or history. The Agent should recall “Lin”; wording depends on the model. Ordinary messages in the current compatibility subset require an idle Session. To redirect active work, a supported ordinary batch is `[{"type":"user.interrupt"}, {"type":"user.message","content":[{"type":"text","text":"Focus on the summary instead."}]}]`. Explicit queued work and targeted interactions are separate platform extensions.

## 8. Continue to the next part

Once the second message has also produced an answer and idle, open [Part 2: Events and streaming](events-and-streaming.en.md). Keep both terminals and the same `SESSION_ID`, `api`, `RUN_ID` and `D`. Continue with the Agent and Session already created here.

`$D/client.sh` saves all shared variables and the request helper. For a new terminal, copy the complete `source /actual/path/client.sh` command printed in step 3. A new terminal does not know `D` yet, so do not start with `source "$D/client.sh"`.

## Compatibility and troubleshooting

| Symptom | Check |
| --- | --- |
| 401 / 403 | Confirm the base URL points to this POC cluster and development identity mode remains enabled |
| Session creation rejected | Model/environment configuration and unsupported Skills/multiagent/client approval |
| Plain message rejected while busy | Wait for idle, or use the supported interrupt-and-message sequence |
| No response on SSE | Confirm `:connected` before sending; inspect history, Runtime, MQ and Redis |
| `session.error` | Read the error; do not infer success from a subsequent idle event |
| Stream disconnected | Fetch history and rebuild observation; do not create a new logical request for transport retry |
| `requires_action` / budget / delta examples in upstream docs | Outside this release; use this deployment's supported subset |

See [API layers and extensions](api-reference.en.md#api-layers), [HTTP MCP examples](../../agentscope-service/docs/managed-mcp-long-tool-cluster-walkthrough.en.md), and [protocol verification](../../agentscope-service/tests/session-protocol/README.md). Source checks establish syntax and the pinned subset; a model-backed deployment still needs its own acceptance run.
