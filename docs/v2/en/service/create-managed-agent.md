---
title: "Run your first Managed Agent"
description: "Use curl to create a Managed Agent, start a session, submit work, and inspect the result."
zh_link: /v2/zh/service/create-managed-agent
---

Create a HarnessAgent-based Managed Agent with curl. It will organize meeting notes, write them to a file, and read the file back. Service runs the Agent; you configure it and submit work through the Session API.

<span id="1-prepare-platform-and-execution-resources"></span>

<span id="sign-in-and-select-a-namespace"></span>

## Prepare

Complete the [Docker Compose quickstart](/v2/en/service/quickstart), configure model credentials, and enable `BUILDER_ALLOW_LOCAL_ENVIRONMENT=true`. The commands require Bash, curl, and jq. Run them in order in one Bash terminal.

<span id="api-setup"></span>

Set your Service address, platform user token, and authorized namespace. For an existing team deployment, use the configuration supplied by your administrator.

```bash
set -euo pipefail
export BASE_URL="http://localhost:18080"
export TOKEN="YOUR_USER_TOKEN"
export TENANT="YOUR_TENANT"
export NAMESPACE="YOUR_NAMESPACE"
```

<Accordion title="Need a user token? Sign in and list namespaces">

Enter your platform username and password. On a new deployment, use `admin` and the password from `.env`, or the password you changed in Profile.

```bash
read -r -p "Username: " LOGIN_USER
read -r -s -p "Password: " LOGIN_PASSWORD
printf '\n'

LOGIN_JSON=$(
  jq -n --arg username "$LOGIN_USER" --arg password "$LOGIN_PASSWORD" \
    '{username: $username, password: $password}' \
  | curl -sS --fail-with-body "$BASE_URL/api/auth/login" \
      -H "Content-Type: application/json" \
      --data-binary @-
)
unset LOGIN_PASSWORD
TOKEN=$(jq -er '.token' <<< "$LOGIN_JSON")

curl -sS --fail-with-body "$BASE_URL/api/v1/me/namespaces" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '.items[] | {tenant, name}'
```

Choose a namespace from the response and use its `tenant` and `name` for `TENANT` and `NAMESPACE` above. The user token manages resources; see [application integration](/v2/en/service/service-api) for application credentials.

</Accordion>

<span id="prepare-execution-resources"></span>

<span id="select-a-tool-execution-environment"></span>

## 1. Create an environment

Create a Local Environment so file tools run inside the Dataplane container. If you already have an available environment, set `ENVIRONMENT_ID` and skip this request. See [Environments](/v2/en/service/environments) for other types.

```bash
ENVIRONMENT_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/environments" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
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
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
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
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
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
    -H "Authorization: Bearer $TOKEN" \
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
  curl -sS --fail-with-body "$SESSION_URL/snapshot" \
    -H "Authorization: Bearer $TOKEN"
)
jq '{items, tools, turns, required_actions}' <<< "$SNAPSHOT"
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

If work is still running, receive SSE events after the snapshot’s `as_of` cursor:

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode "after=$CURSOR"
```

After receiving `turn.completed` for your `TURN_ID`, press Ctrl-C to stop the stream and query the final result. Closing the stream does not cancel the task.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '{id, status, error}'

curl -sS --fail-with-body "$SESSION_URL/snapshot" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '{items, tools, required_actions}'
```

Confirm the Turn’s `status` is `completed`, tool records show `meeting-actions.md` was written and read, and the response preserves the Friday deadline, Monday review, and unconfirmed time. The file resides in the execution environment; see [files and artifacts](/v2/en/service/files) for downloads and Artifact publication.

If the status is `failed`, inspect `error`. For `requires_action`, inspect the snapshot’s `required_actions`. See [sessions and tasks](/v2/en/service/session-event-log) for handling these states.

<span id="add-capabilities-and-publish"></span>

## Next

Keep `AGENT_ID` and continue to [Session API application integration](/v2/en/service/service-api). To add capabilities, see [Agent configuration](/v2/en/service/managed-agent-configuration), [tools and MCP](/v2/en/service/tools), and [SSE events](/v2/en/service/sse-events).
