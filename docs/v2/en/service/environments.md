---
title: "Execution environments"
description: "Run Session tools in Hands, Service-managed ACS sandboxes, or user-owned instances."
zh_link: /v2/zh/service/environments
---

Environment selects where a Managed Agent's file and shell tools run. HarnessAgent reasons in Dataplane; Hands executes the tools and returns their results to the same Turn. Worker credentials authorize access to an Environment, while `config.type` selects execution. Starting Hands with a platform token does not force cloud execution.

## Choose an execution mode

| `config.type` | Execution | Resource ownership |
| --- | --- | --- |
| `local` | Shell and file tools run in the Hands container or host | No remote Sandbox is created |
| `cloud` | Hands creates and reuses an ACS Sandbox for each Session | Service manages creation, TTL renewal and cleanup |
| `self_hosted` | Hands connects to an existing E2B-compatible instance | User manages creation, lifetime and deletion |
| `remote` | Existing distributed filesystem integration | Filesystem only; outside this Hands workflow |

Local has a separate directory per Session, but shares the Hands operating system. An administrator must enable `BUILDER_ALLOW_LOCAL_ENVIRONMENT=true`. Cloud supports ACS native E2B routing first; `/kruise/...` private routing is not supported by this workflow. Prepare SandboxManager, a template, DNS/TLS and a tenant API key using the [ACS setup guide](https://help.aliyun.com/zh/cs/user-guide/connect-to-agent-sandbox-using-the-e2b-sdk). Templates need `/bin/bash`, `python3` and `tar` for Hands tools and workspace archives.

For self_hosted, Hands must be able to reach the user's SandboxManager and execution endpoint. The user supplies the Sandbox API key in this Environment; platform cloud credentials are never used as its fallback. Connecting uses instance lookup and does not renew its TTL, recreate it, restore an archive over it, or delete it. The user must keep the instance alive. A borrowed instance is reserved for one Session; deleting an idle Session releases this reservation without deleting the instance. Hands does not automatically stage Agent definition files into a user-owned workspace. Reuse that Session for later Turns rather than binding the same instance to a second Session.

## 1. Create an Environment

Use the Gateway URL of your deployment; the existing local portal stack uses port 18081. These examples assume local development without API authentication. For an authenticated installation, also include your authorized user or application headers in each public API call.

```bash
export BASE_URL="http://localhost:18081"
curl -sS --fail-with-body "$BASE_URL/actuator/health"
```

Create a local Environment after enabling Local in the control plane:

```bash
ENVIRONMENT_JSON=$(curl -sS --fail-with-body "$BASE_URL/api/environments" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Hands local demo","config":{"type":"local"}}') || exit 1
export ENVIRONMENT_ID=$(jq -er '.id' <<< "$ENVIRONMENT_JSON")
export ENVIRONMENT_KEY=$(jq -er '.apiKey' <<< "$ENVIRONMENT_JSON")
```

For cloud, use this body instead. `templateId` is the ACS SandboxSet template name. `apiBaseUrl` defaults to `https://api.<domain>` when omitted. Set `config.apiKey`, or configure the Service's cloud fallback `BUILDER_E2B_API_KEY` before starting Dataplane.

```json
{
  "name": "ACS cloud",
  "config": {
    "type": "cloud",
    "provider": "acs",
    "domain": "sandbox.example.com",
    "templateId": "code-interpreter",
    "workspaceRoot": "/home/user",
    "sandboxTimeoutSeconds": 300
  }
}
```

For self_hosted, use your own connection settings and key. This is the Sandbox provider key, distinct from the Environment key returned by Service.

```json
{
  "name": "User Sandbox",
  "config": {
    "type": "self_hosted",
    "provider": "e2b",
    "apiBaseUrl": "https://api.user-sandbox.example.com",
    "domain": "user-sandbox.example.com",
    "apiKey": "USER_SANDBOX_TENANT_KEY",
    "workspaceRoot": "/home/user"
  }
}
```

You may set `config.sandboxId` for a dedicated instance, or pass `agentscope.sandbox_id` when creating its Session. A Session value takes precedence. `provider: acs` is also accepted for a user-owned ACS instance. Direct credential configuration is an AgentScope extension; access to Environment configuration must be restricted to authorized operators.

## 2. Start Hands

Build the current source or use the matching Hands image. The Compose profile runs a platform-authenticated Worker and can handle all three modes for its configured Environment:

```bash
# Set BUILDER_HANDS_ENVIRONMENT_ID in the deployment env file first.
# Run from agentscope-service with the same env file and project options as your stack.
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile hands up -d hands
```

Keep `/data/hands` durable for the Worker journal and `/data/workspaces` shared for cloud archives. Helm provides the same Worker with `hands.enabled=true` and `hands.environmentID`; cloud replicas need shared archive storage.

A customer-side Worker can use an Environment key to execute local or self_hosted tasks. It cannot claim cloud tasks or obtain platform cloud credentials. Start it in a network that can reach the user instance:

```bash
java -jar agentscope-service/service-hands/target/service-hands-2.1.0-SNAPSHOT.jar \
  --base-url "$BASE_URL" --environment-id "$ENVIRONMENT_ID" \
  --environment-key "$ENVIRONMENT_KEY" \
  --hands-root /tmp/agentscope-hands-demo --worker-id demo-hands
```

Replace the JAR version with the artifact produced by your build. Each Worker uses one Environment ID. Different Worker processes need separate journals; the local workspace remains pinned to its Worker. Both authentication choices use the same execution dispatch based on the Environment.

## 3. Create an Agent and Session

Create a Managed Agent using [your first Agent](/v2/en/service/create-managed-agent), select this Environment and enable the `bash`, `read` and `write` tool configurations. These toolset names project to the execute, read_file and write_file calls used by Hands. Save its ID as `AGENT_ID`.

```bash
SESSION_JSON=$(jq -n --arg agent "$AGENT_ID" --arg environment "$ENVIRONMENT_ID" \
  '{agent:$agent,environment_id:$environment,title:"Sandbox demo"}' \
  | curl -sS --fail-with-body "$BASE_URL/v1/sessions" \
      -H 'Content-Type: application/json' --data-binary @-) || exit 1
export SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
export SESSION_URL="$BASE_URL/v1/sessions/$SESSION_ID"
```

For a self_hosted Environment without `config.sandboxId`, add this extension to the create body:

```json
"agentscope": { "sandbox_id": "EXISTING_SANDBOX_ID" }
```

Creating the Environment does not prove that Hands is connected or the provider is reachable. Creating a self_hosted Session reserves the instance; duplicate reservations return 409. Execution configuration is frozen for the Session, so Environment edits apply to new Sessions.

## 4. Test tools, long tasks and interruption

In another terminal, open the stream using the same `SESSION_URL` and authentication:

```bash
curl -N -sS --fail-with-body "$SESSION_URL/events/stream"
```

Send a task that writes and reads a file:

```bash
curl -sS --fail-with-body "$SESSION_URL/events" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: sandbox-file-001' \
  -d '{"events":[{"type":"user.message","content":[{"type":"text","text":"Use execute to run uname -a. Write the result to platform.txt, read it back, and report the contents."}]}]}' | jq
```

Confirm tool calls, their results and the final answer in the stream or event history. In local mode, inspect `/data/hands/workspaces` inside Hands. In cloud or self_hosted mode, inspect the selected remote instance; the command must execute there.

Read the authorized tool-task view to locate the cloud or borrowed instance ID:

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions/$SESSION_ID/tool-tasks" \
  | jq '.data[] | {task_id, environment_type, sandbox_id, status}'
```

Use a new idempotency key to send: `Run execute with command sleep 60; printf done and timeout 120. Wait for completion and report the output.` A silent command must not fail after 15 seconds. The tool execution deadline controls the streaming request; short management HTTP timeouts remain separate.

For interruption, send a longer command, wait for its tool call, then:

```bash
curl -sS --fail-with-body "$SESSION_URL/events" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: sandbox-stop-001' \
  -d '{"events":[{"type":"user.interrupt"}]}' | jq
```

Check the remote process list or a test heartbeat file to confirm execution stopped. Hands sends a process termination request and checks its absence; it does not delete a user instance. If termination cannot be confirmed, the task remains unknown and its execution binding stays fenced for recovery. A disconnected stream does not establish that a process exited.

Send another user.message with a new key to continue in a new Turn using the same Session and workspace. Automatic tool results continue the original waiting Turn. Reusing the same batch key retries acceptance rather than submitting another message.

## Configuration and recovery limits

| Field | Scope and behavior |
| --- | --- |
| `config.type` | local, cloud or self_hosted; top-level type is accepted but must agree |
| `provider` | cloud: acs; self_hosted: e2b or acs |
| `domain` / `apiBaseUrl` | Native E2B execution domain / management API |
| `envdBaseUrl` | Optional fixed execution proxy using Sandbox ID headers |
| `templateId` | Required for cloud; supplied by ACS configuration |
| `sandboxId` | Optional existing instance for self_hosted |
| `workspaceRoot` | Remote working directory, default /home/user |
| `runUser` / `codec` | Remote user and Connect stream codec, default user / PROTO |
| `readTimeoutSeconds` | Management/client setting; process streaming uses its execution deadline |
| `sandboxTimeoutSeconds` | cloud TTL, bounded to 120–3600 seconds by Hands |
| `sandboxIdleTimeoutSeconds` / `sandboxMaxLifetimeSeconds` | cloud cleanup thresholds, default 300 / 3600 seconds |

Cloud currently archives the workspace as TAR after a known tool result. Provider memory snapshots and pause/resume are not enabled by setting `persistenceMode`. A recovered archive restores files, not a running process. Unconfirmed writes and remote executions are not automatically rerun. A Worker restart retries saved results independently of execution.

The upgrade migrates previous sandbox Environments to cloud and previous local-execution self_hosted Environments to local. Old encrypted runtime snapshots are normalized when read. Back up the database and stop old Workers before running the migration jobs; then start all updated planes and Hands. The local compose override enables Local for development. This source/configuration update does not itself upgrade your running containers.
