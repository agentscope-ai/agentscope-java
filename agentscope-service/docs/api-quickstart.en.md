# API quickstart: a Session and its events

[中文](api-quickstart.md) · [Project overview](../../README.md) · [API reference](api-reference.en.md) · [Events and streaming](events-and-streaming.en.md)

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

Use a current deployment with schema migrations applied, Runtime and Hands running, and a valid server-side model configuration. Follow the [deployment guide](../deploy/README.md) to prepare PostgreSQL, Redis and MQ. This example disables tools and does not request files or Sandbox operations.

- **Local development:** enable `BUILDER_LOCAL_DEV=true`, use `http://localhost:18080`, leave `TOKEN` empty, and allow the default local Environment.
- **Authenticated deployment:** set the public URL, a user JWT in `TOKEN`, your `TENANT`/`NAMESPACE`, and an authorized runnable `ENVIRONMENT_ID`. Login uses `POST /api/auth/login`; Agent creation requires a management identity and resource permissions.

The cURL walkthrough uses the same JWT for management and Session calls. An Application key can instead authenticate Session APIs with `X-API-Key`; it cannot replace the management identity used to create the Agent. The SDK example below supports either mode. Credentials here belong to this deployment, not to Anthropic or the model provider.

You need Bash, `curl` with `--fail-with-body`, and `jq`. Keep the IDs from this dedicated Session and avoid concurrent writers while following the example.

## 1. Prepare the API helper (terminal A)

Run the setup blocks in one Bash shell. Responses are saved outside the repository.

```bash
# Bash
set -euo pipefail
export BASE_URL="${BASE_URL:-http://localhost:18080}"
export TENANT="${TENANT:-default}"
export NAMESPACE="${NAMESPACE:-default}"
export TOKEN="${TOKEN:-}"
export ENVIRONMENT_ID="${ENVIRONMENT_ID:-}"
export RUN_ID="quickstart-$(date +%s)-$$"
export D=$(mktemp -d "${TMPDIR:-/tmp}/agentscope-service-api.XXXXXX")

api() {
  local path="$1"
  shift
  local auth=()
  if [[ -n "${TOKEN:-}" ]]; then
    auth=(-H "Authorization: Bearer $TOKEN")
  fi
  curl --fail-with-body -sS "$BASE_URL$path" \
    "${auth[@]}" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' \
    -H 'anthropic-version: 2023-06-01' \
    -H 'anthropic-beta: managed-agents-2026-04-01' \
    "$@"
}

api /api/v1/version | jq .
```

## 2. Create an Agent (platform setup)

Managed binding plus definition creates a runnable Agent. `ENVIRONMENT_ID` selects the supplied environment; local development can provision its default. The model comes from deployment configuration and tools are explicitly disabled.

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

Keep its ID for this conversation. Read its initial `idle` state, then save the public parameters/helper for terminal B. The saved helper contains no token; provide credentials again in terminal B when authentication is enabled.

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

{
  printf 'export BASE_URL=%q\n' "$BASE_URL"
  printf 'export TENANT=%q\n' "$TENANT"
  printf 'export NAMESPACE=%q\n' "$NAMESPACE"
  printf 'export SESSION_ID=%q\n' "$SESSION_ID"
  printf 'export RUN_ID=%q\n' "$RUN_ID"
  printf 'export D=%q\n' "$D"
  declare -f api
} > "$D/client.sh"
printf 'In terminal B, run: source %q\n' "$D/client.sh"
```

## 4. Open the event stream first (terminal A)

```bash
api "/v1/sessions/$SESSION_ID/events/stream" -N
```

Wait for the `:connected` comment before sending. That frame confirms this implementation has captured the stream boundary. Keep terminal A open.

## 5. Send user.message (terminal B)

Open another Bash terminal and run the exact `source .../client.sh` command printed in step 3. If authenticated, export the same `TOKEN` again before calling the helper. Then send:

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

## Official Python SDK example

This alternative creates a **fresh** Session using the Agent/environment from terminal A, then runs two sequential interactions and reads history. Use the repository's pinned compatibility baseline rather than assuming every newer SDK feature is supported:

```bash
python3 -m venv "$D/sdk-venv"
"$D/sdk-venv/bin/python" -m pip install 'anthropic==1.15.0'
```

Save this as `$D/session-events.py` and run it from terminal A, where `AGENT_ID`, `BASE_URL`, `RUN_ID` and your environment/credentials are available:

```python
import os
from anthropic import Anthropic, omit

# These are this deployment's credentials, not a model-provider API key.
token = os.environ.get("TOKEN", "")
api_key = os.environ.get("SESSION_API_KEY", "") or "local-development"
if token and os.environ.get("SESSION_API_KEY"):
    raise ValueError("Choose TOKEN or SESSION_API_KEY for Session calls")

with Anthropic(
    base_url=os.environ["BASE_URL"],
    api_key=api_key,
    auth_token="",
    max_retries=0,
    timeout=180.0,
    default_headers={
        "X-Api-Key": omit if token else api_key,
        "Authorization": f"Bearer {token}" if token else omit,
        "X-AgentScope-Tenant": os.environ.get("TENANT", "default"),
        "X-AgentScope-Namespace": os.environ.get("NAMESPACE", "default"),
    },
) as client:
    request_key = os.environ["RUN_ID"] + "-sdk"
    create = {
        "agent": os.environ["AGENT_ID"],
        "title": "Event API quickstart",
        "extra_headers": {"Idempotency-Key": request_key + "-session"},
    }
    if os.environ.get("ENVIRONMENT_ID"):
        create["environment_id"] = os.environ["ENVIRONMENT_ID"]
    session = client.beta.sessions.create(**create)
    print("Session:", session.id, flush=True)

    def converse(text, key):
        # Entering the context opens the HTTP stream before the write.
        with client.beta.sessions.events.stream(session.id) as stream:
            accepted = client.beta.sessions.events.send(
                session.id,
                events=[{
                    "type": "user.message",
                    "content": [{"type": "text", "text": text}],
                }],
                extra_headers={"Idempotency-Key": key},
            )
            print("Accepted:", accepted.data[0].id, flush=True)
            for event in stream:
                if event.type == "agent.message":
                    for block in event.content:
                        if block.type == "text":
                            print(block.text, flush=True)
                elif event.type == "session.error":
                    detail = getattr(event, "error", None)
                    raise RuntimeError(getattr(detail, "message", "Session execution failed"))
                elif event.type == "session.status_idle":
                    reason = getattr(getattr(event, "stop_reason", None), "type", None)
                    if reason != "end_turn":
                        raise RuntimeError(f"Session needs attention: {reason}")
                    return
            raise RuntimeError("Stream closed before idle; inspect Session event history")

    converse("My name is Lin. Introduce yourself in one sentence.", request_key + "-message-1")
    converse("What name did I tell you?", request_key + "-message-2")

    # The SDK follows the history pages; no Turn ID is needed.
    for event in client.beta.sessions.events.list(session.id, limit=100, order="asc"):
        print(event.id, event.type, event.processed_at)
```
```bash
"$D/sdk-venv/bin/python" "$D/session-events.py"
```

Use `TOKEN` for a user JWT or `SESSION_API_KEY` for an authorized Application key; choose one. With neither set, `local-development` is a placeholder accepted only by explicitly enabled local development mode. The `omit` selections ensure no request carries both authentication headers, even when the shell has unrelated SDK credential variables. This script calls the official Session/Events SDK surface only, with a custom base URL.

If the stream fails or closes before idle, inspect event history and the Session before retrying the accepted write. A frozen receipt does not replay old response events onto a newly opened stream. The repository's existing official-SDK contract tests validate a pinned subset; they do not establish complete Claude Managed Agents compatibility.

## Compatibility and troubleshooting

| Symptom | Check |
| --- | --- |
| 401 / 403 | Deployment credential, namespace and Agent/Environment/Session permissions |
| Session creation rejected | Model/environment configuration and unsupported Skills/multiagent/client approval |
| Plain message rejected while busy | Wait for idle, or use the supported interrupt-and-message sequence |
| No response on SSE | Confirm `:connected` before sending; inspect history, Runtime, MQ and Redis |
| `session.error` | Read the error; do not infer success from a subsequent idle event |
| Stream disconnected | Fetch history and rebuild observation; do not create a new logical request for transport retry |
| `requires_action` / budget / delta examples in upstream docs | Outside this release; use this deployment's supported subset |

See [API layers and extensions](api-reference.en.md#api-layers), [HTTP MCP examples](managed-mcp-long-tool-cluster-walkthrough.en.md), and [protocol verification](../tests/session-protocol/README.md). Source checks establish syntax and the pinned subset; a model-backed deployment still needs its own acceptance run.
