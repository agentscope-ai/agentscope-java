---
title: "Run your first Managed Agent"
description: "Use curl to create a Managed Agent, start a session, submit work, and inspect the result."
zh_link: /v2/zh/service/create-managed-agent
---

Create a HarnessAgent-based Managed Agent with curl. It will organize meeting notes, write them to a file, and read the file back. Service runs the Agent; you configure it and submit work through the Session API.

<span id="1-prepare-platform-and-execution-resources"></span>

<span id="sign-in-and-select-a-namespace"></span>

This file-tool example requires user files enabled (the source Compose default). If production
uses `userFilesEnabled=false`, start with the [tool-free native API quickstart](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-service/docs/api-quickstart.en.md), or configure the shared storage and enable user files before running this example.

## Prepare

Complete the [local Docker Compose quickstart](/v2/en/service/quickstart) and configure model credentials. The commands require Bash or zsh, curl, and jq. Run them in order in one terminal.

<span id="api-setup"></span>

Local development needs no sign-in, user token, or application key and uses the default namespace. Set your Service URL and default scope variables. See [production deployment](/v2/en/service/kubernetes#production-api-access) for production identity setup.

```bash
set -euo pipefail
export BASE_URL="http://localhost:18080"
export TENANT="default"
export NAMESPACE="default"

native_api() {
  curl -sS --fail-with-body -H 'AgentScope-Extensions: turn-inputs-v1' \
    -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" "$@"
}
```


<span id="prepare-execution-resources"></span>

<span id="select-a-tool-execution-environment"></span>

## 1. Create an environment

Create a Local Environment so file tools execute through Hands. If you already have an available environment, set `ENVIRONMENT_ID` and skip this request. See [Environments](/v2/en/service/environments) for other types.

```bash
ENVIRONMENT_JSON=$(
  native_api "$BASE_URL/api/environments" \
    -H "Content-Type: application/json" \
    --data-binary @- <<'JSON'
{
  "name": "Quickstart local",
  "type": "local",
  "config": {}
}
JSON
)
ENVIRONMENT_ID=$(jq -er '.id' <<< "$ENVIRONMENT_JSON")
```

<span id="create-the-identity-and-managed-definition"></span>

<span id="2-create-the-notes-assistant"></span>

Local tools execute through the platform Hands component installed with Service. Before sending a task, verify that Hands is healthy using the [Environment guide](/v2/en/service/environments#2-start-hands). Creating an Environment resource alone does not prove execution readiness; no separate Hands profile or fixed Environment ID is required by the platform service.

## 2. Create an Agent

Create a notes assistant using the deployment’s default model and only the file reading and writing tools. Set `binding.kind` to `managed` and select the tool environment with `defaultEnvironmentId`.

```bash
AGENT_JSON=$(
  native_api "$BASE_URL/api/v1/agents" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE",
  "agentKey": "notes-assistant-api",
  "displayName": "Notes assistant",
  "binding": { "kind": "managed" },
  "definition": {
    "name": "Notes assistant",
    "system": "Organize meeting notes into action items. Preserve facts and mark missing information as unconfirmed. Write the file, read it back, and include its contents in the final response.",
    "defaultEnvironmentId": "$ENVIRONMENT_ID",
    "tools": [{
      "type": "agent_toolset",
      "defaultConfig": { "enabled": false },
      "configs": [
        { "name": "read", "enabled": true, "permissionPolicy": { "type": "always_allow" } },
        { "name": "write", "enabled": true, "permissionPolicy": { "type": "always_allow" } }
      ]
    }]
  }
}
JSON
)
AGENT_ID=$(jq -er '.agent.id' <<< "$AGENT_JSON")
printf 'Agent ID: %s\n' "$AGENT_ID"
```

Save `AGENT_ID` to reuse this Agent in later sessions. Use a different `agentKey` when creating another Agent. This example allows file reads and writes without confirmation; see [tool permissions](/v2/en/service/tools#tool-permissions) for configuration.

<span id="create-a-session-and-submit-work"></span>

<span id="3-create-a-session-and-submit-a-task"></span>

## 3. Create a session

Create a Session referencing the Agent to retain messages and execution history. Creating a session does not start work; submit a task next.

```bash
SESSION_JSON=$(
  native_api "$BASE_URL/v1/sessions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-session-001" \
    --data-binary @- <<JSON
{
  "agent": "$AGENT_ID",
  "environment_id": "$ENVIRONMENT_ID"
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
export SESSION_URL="$BASE_URL/v1/sessions/$SESSION_ID"
printf 'Session ID: %s\n' "$SESSION_ID"
```

## 4. Submit a task

Submit a `user.message` event with `intent:new_turn` to create a Turn. Current native writes use `/events`; `/turns` is a read extension, not a submission route.

```bash
TURN_JSON=$(
  native_api "$SESSION_URL/events" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-check-001" \
    --data-binary @- <<'JSON'
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Li will finish the installation guide on Friday; review it next Monday, exact time unconfirmed. Organize the action items, write meeting-actions.md, read it back, and include the file contents."}],
    "agentscope_service": {"intent": "new_turn"}
  }]
}
JSON
)
TURN_ID=$(jq -er '.data[0].agentscope_service.turn_id' <<< "$TURN_JSON")
jq . <<< "$TURN_JSON"
```

A successful 2xx response means the task was admitted; execution continues in the background. For a network retry, keep the same `Idempotency-Key` and message. Use a new key for a new task.

<span id="4-read-progress-and-tool-results"></span>

<span id="5-verify-delivery"></span>

## 5. Inspect progress and results

Poll the Turn until it finishes, preserving the same Session and Turn IDs:

```bash
for attempt in {1..90}; do
  TURN=$(native_api "$SESSION_URL/turns/$TURN_ID")
  STATUS=$(jq -er '.status' <<< "$TURN")
  case "$STATUS" in
    completed) break ;;
    failed|cancelled) jq . <<< "$TURN"; exit 1 ;;
  esac
  sleep 2
done
jq '{id, status, error}' <<< "$TURN"
```

Read public history and final output. If the Turn is still running, retain the IDs and query again;
a timeout in this polling example does not cancel execution.

```bash
native_api "$SESSION_URL/events?limit=100" | jq .
native_api "$SESSION_URL/turns/$TURN_ID/output?limit=100" | jq .
```

Confirm `status` is `completed`, execution history shows the file operations, and the response
preserves the Friday deadline, Monday review and unconfirmed time. The file remains in the
selected execution environment. The first native protocol does not expose public file/Artifact routes; inspect the file through that Environment's own access mechanism.

The current native contract does not provide snapshots or SSE replay cursors. Live SSE starts
after connection; use paginated history and Turn/output queries to recover state after reconnecting.
Inspect Turn errors and public history when execution fails or waits for input. Follow the
[current Managed Session contract](/v2/en/service/claude-session-api) for supported controls and limits.

<span id="add-capabilities-and-publish"></span>

## Next

Keep `AGENT_ID` and continue to [Session API application integration](/v2/en/service/claude-session-api). To add capabilities, see [Agent configuration](/v2/en/service/managed-agent-configuration), [tools and MCP](/v2/en/service/tools), and [SSE events](/v2/en/service/sse-events).
