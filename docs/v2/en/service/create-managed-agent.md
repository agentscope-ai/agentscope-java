---
title: "Run your first Managed Agent"
description: "Use curl to create a Managed Agent, start a session, submit work, and inspect the result."
zh_link: /v2/zh/service/create-managed-agent
---

Create a HarnessAgent-based Managed Agent with curl. It will organize meeting notes, write them to a file, and read the file back. Service runs the Agent; you configure it and submit work through the Session API.

<span id="1-prepare-platform-and-execution-resources"></span>

<span id="sign-in-and-select-a-namespace"></span>

## Prepare

Complete the [local Docker Compose quickstart](/v2/en/service/quickstart) and configure model credentials. The commands require Bash or zsh, curl, and jq. Run them in order in one terminal.

<span id="api-setup"></span>

Local development needs no sign-in, user token, or application key and uses the default namespace. Set your Service URL and default scope variables. See [production deployment](/v2/en/service/kubernetes#production-api-access) for production identity setup.

```bash
set -euo pipefail
export BASE_URL="http://localhost:18080"
export TENANT="default"
export NAMESPACE="default"
```


<span id="prepare-execution-resources"></span>

<span id="select-a-tool-execution-environment"></span>

## 1. Create an environment

Create a Local Environment so file tools run inside the Dataplane container. If you already have an available environment, set `ENVIRONMENT_ID` and skip this request. See [Environments](/v2/en/service/environments) for other types.

```bash
ENVIRONMENT_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/environments" \
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

## 2. Create an Agent

Create a notes assistant using the deployment’s default model and only the file reading and writing tools. Set `binding.kind` to `managed` and select the tool environment with `defaultEnvironmentId`.

```bash
AGENT_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agents" \
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
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "agent",
    "id": "$AGENT_ID"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
export SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
printf 'Session ID: %s\n' "$SESSION_ID"
```

## 4. Submit a task

Send a message to the session to create a Turn.

```bash
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-check-001" \
    --data-binary @- <<'JSON'
{
  "message": "Li will finish the installation guide on Friday; review it next Monday, exact time unconfirmed. Organize the action items, write meeting-actions.md, read it back, and include the file contents."
}
JSON
)
TURN_ID=$(jq -er '.id' <<< "$TURN_JSON")
jq '{id, status}' <<< "$TURN_JSON"
```

A `202 Accepted` response means the task was received. For a network retry, keep the same `Idempotency-Key` and message. Use a new key for a new task.

<span id="4-read-progress-and-tool-results"></span>

<span id="5-verify-delivery"></span>

## 5. Inspect progress and results

Read the snapshot to inspect existing messages, tool results, and task status:

```bash
SNAPSHOT=$(
  curl -sS --fail-with-body "$SESSION_URL/snapshot"
)
jq '{items, tools, turns, required_actions}' <<< "$SNAPSHOT"
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

If work is still running, receive SSE events after the snapshot’s `as_of` cursor:

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  --data-urlencode "after=$CURSOR"
```

After receiving `turn.completed` for your `TURN_ID`, press Ctrl-C to stop the stream and query the final result. Closing the stream does not cancel the task.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  | jq '{id, status, error}'

curl -sS --fail-with-body "$SESSION_URL/snapshot" \
  | jq '{items, tools, required_actions}'
```

Confirm the Turn’s `status` is `completed`, tool records show `meeting-actions.md` was written and read, and the response preserves the Friday deadline, Monday review, and unconfirmed time. The file resides in the execution environment; see [files and artifacts](/v2/en/service/files) for downloads and Artifact publication.

If the status is `failed`, inspect `error`. For `requires_action`, inspect the snapshot’s `required_actions`. See [sessions and tasks](/v2/en/service/session-event-log) for handling these states.

<span id="add-capabilities-and-publish"></span>

## Next

Keep `AGENT_ID` and continue to [Session API application integration](/v2/en/service/service-api). To add capabilities, see [Agent configuration](/v2/en/service/managed-agent-configuration), [tools and MCP](/v2/en/service/tools), and [SSE events](/v2/en/service/sse-events).
