---
title: "Environments: execution locations"
description: "Prepare a tool execution backend, select it through Agent defaults or Session creation, and verify where tools run."
zh_link: /v2/zh/service/environments
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

An Environment selects the execution backend for a Managed Agent's file, Shell, and related tools. After creating it, save its ID as `defaultEnvironmentId` in the Agent definition or select it through `environmentId` when creating a Session. The first supplies a default for the Agent's new Sessions; the second selects the location for this Session only. [Agent tool configuration](/v2/en/service/tools) still controls tool availability and confirmation.

A [Workspace](/v2/en/service/workspaces) supplies instructions, Skills, and tool definitions, while the Environment determines where files and commands are executed. Managed model calls and reasoning remain in Dataplane even when tools run on a self_hosted Worker. A Hosted Agent's Runtime Host runs another kind of Agent runtime; its enrollment credentials cannot replace an Environment Worker's credentials.

Use the platform identity variables from the [API identity setup](/v2/en/service/create-managed-agent#api-setup) and `AGENT_ID` from [your first Managed Agent](/v2/en/service/create-managed-agent) to verify file tools. If a suitable Environment already exists, retain its ID and continue to binding. When creating one yourself, also prepare its execution backend: a successful resource creation does not establish that a Worker is online or sandbox credentials are usable.

## Choose a type

| Type | Execution model |
| --- | --- |
| local | Runs with the Dataplane; requires Local to be enabled by an administrator |
| sandbox | Isolated Shell/filesystem execution through E2B |
| remote | Shared BaseStore filesystem without Shell execution |
| self_hosted | Execution supplied by a Worker you operate |

In Docker, Local means inside the Dataplane container, not arbitrary access to the host filesystem. Choose a backend according to production isolation and networking requirements.

## Create an execution environment

This request creates a `self_hosted` Environment and returns its resource ID and a one-time `apiKey`. The ID selects the resource for an Agent or Session, while the key authenticates the Worker connecting to the platform. Neither serves the same purpose as the user `TOKEN` used for management APIs.

```bash
ENVIRONMENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/environments" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"name":"Report worker","type":"self_hosted","config":{}}')
ENVIRONMENT_ID=$(printf '%s' "$ENVIRONMENT_JSON" | jq -er '.id')
ENVIRONMENT_KEY=$(printf '%s' "$ENVIRONMENT_JSON" | jq -er '.apiKey')
```

Retain `ENVIRONMENT_ID` and `ENVIRONMENT_KEY`, then start the Worker below. Other types require their own backend preparation: Local requires administrator permission, while Sandbox requires working E2B configuration. Changing `type` in the request does not perform that preparation.

<span id="self-hosted-execution"></span>

## Run a self-hosted Worker from the published image

Create a self_hosted Environment and save its API key. Set `SCHEDULER_IMAGE` to the full scheduler image reference from the manifest, `BASE_URL` to a Gateway URL reachable from the Worker, and `ENVIRONMENT_ID`/`ENVIRONMENT_KEY` to the new Environment's values.

```bash
docker run --rm \
  --name agentscope-hands \
  -v agentscope-hands:/data \
  --entrypoint java "$SCHEDULER_IMAGE" \
  -Dloader.main=io.agentscope.builder.worker.HandsWorkerMain \
  -cp /app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --base-url "$BASE_URL" \
  --environment-id "$ENVIRONMENT_ID" \
  --environment-key "$ENVIRONMENT_KEY" \
  --hands-root /data/hands \
  --worker-id hands-1
```

The Worker makes outbound Gateway requests without exposing an inbound port. Bind a Managed Agent to this Environment, request a small file read/write and observe tool suspension followed by Worker results and resumed execution. Working files persist in the named volume. Preinstall task-specific programs in your Worker image.

Use your process/container manager for restarts and distinct worker IDs for multiple Workers. Inspect claimed work before stopping; process shutdown is not business-task cancellation.

## Bind and configure

### Select an environment for one Session

Once the Worker or other execution backend is ready, pass `ENVIRONMENT_ID` when creating a Session. This does not change the Agent's default, so it is useful for verifying a new environment or running the same Agent in different environments for different work. The resource must be accessible to the current identity.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" --arg env "$ENVIRONMENT_ID" \
    '{target:{type:"agent",id:$agent},environmentId:$env}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
printf '%s' "$SESSION_JSON" | jq '{id, target, environmentId}'
```

### Set the Agent's default environment

To use this environment by default in the Agent's future Sessions, write the following to `resource-defaults.json` and follow the complete GET/PATCH procedure in [setting default resources](/v2/en/service/managed-agent-configuration#set-default-resources-on-an-agent). That procedure retains tools, instructions, and other resource bindings and supplies the current definition version.

```bash
jq -n --arg env "$ENVIRONMENT_ID" '{defaultEnvironmentId:$env}' > resource-defaults.json
```

New Sessions then inherit the default when they omit `environmentId`. An explicit ID overrides the environment only for that Session. Changing the default does not move existing Sessions. An existing Managed Session can PATCH its own `environmentId`; do so after current work finishes and verify subsequent tasks. Changing the binding does not copy working files from the previous environment.

### Verify actual execution

Creating a Session records the environment selection without executing tools. With the file tools already enabled by [your first Managed Agent](/v2/en/service/create-managed-agent), submit a task to `$SESSION_URL/turns` that writes and reads back `environment-check.txt`. Inspect tool records in snapshots and events. The file belongs in the selected backend's working directory, not the terminal directory running curl. For a Worker, also inspect its tool claims and returned results.

If the Agent responds but file operations fail, inspect the Session's `environmentId`, then check that backend's connectivity, directory mounts, program dependencies, and permissions. Agent instructions cannot supply missing backend files or executables. If a required tool is disabled, update the Agent definition using the [tool guide](/v2/en/service/tools) and verify it in a new Session.

## E2B sandbox example

An administrator supplies `BUILDER_E2B_API_KEY` through deployment configuration. Create a sandbox Environment and use this Config:

```json
{
  "templateId": "base",
  "isolationScope": "SESSION",
  "sandboxTimeoutSeconds": 300
}
```

Select a custom E2B template for additional executables. `workspaceRoot` controls the sandbox path; `persistenceMode` can be `TAR` or `NATIVE_SNAPSHOT`. Verify save/restore with the chosen template and backend. The remote type is filesystem-only, not a remote Shell Worker.

### Sandbox parameters

Set these fields in the Environment's Config. Omitted E2B connection settings inherit administrator deployment configuration.

| Field | Meaning and fallback |
| --- | --- |
| `templateId` | E2B template; `base` without a deployment override |
| `workspaceRoot` | Sandbox working path; `/home/user` without a deployment override |
| `sandboxTimeoutSeconds` | Sandbox lifetime timeout in seconds; 300 without a deployment override |
| `isolationScope` | Harness filesystem isolation scope; defaults to `SESSION` |
| `persistenceMode` | `TAR` or `NATIVE_SNAPSHOT`; defaults to TAR unless overridden in deployment |
| `apiBaseUrl` / `domain` | Custom E2B endpoint settings; usually inherited from deployment |
| `apiKey` | Per-environment E2B credential override; usually configured centrally |

Adding `packages`, Docker image or network fields to Config does not install dependencies or enforce network restrictions. Prepare programs in the E2B template and apply network policy in the actual backend. These sandbox settings do not configure local or self_hosted containers.

## Interface tour

<Frame caption="Current console UI with fixed demonstration data.">
  <img src="/imgs/service/environments.png" alt="Local and self_hosted environment examples" />
</Frame>

Create and maintain execution environments under **Resources → Environments**. Select a default on the Managed Agent's **Runtime configuration → Session defaults → Default environment**, then create a new Session and inspect its selection before submitting work. The resource page maintains the backend, while the Agent page chooses a default; resource creation alone does not establish that an Agent uses it.

## Diagnose tool failures

Distinguish resource-selection errors from backend-execution errors. For selection errors, inspect Agent defaults, explicit Session choices, and the caller's resource permissions. For execution errors, inspect Worker availability, sandbox credentials, directories, and dependencies. Archiving, deleting, or reconfiguring an Environment can affect other consumers; inspect usage before maintenance. These operations do not replace Turn cancellation.

Environment `config` belongs to the resource, and PATCH replaces the entire object. Read it first and preserve unrelated fields. Unlike a Workspace publication, it has no published revision; a pinned Agent definition does not isolate an existing Session from changes to its execution backend. Prefer a new Session to verify configuration changes, and update all connecting Workers after key rotation.

`config.memoryAccess` accepts `read_only` or `read_write` by Store ID, but the current Managed HarnessAgent execution path mounts shared knowledge read-only. Setting `read_write` does not enable writes through that path or bind a Store. See [Memory access](/v2/en/service/memory#read-and-write-access) for the distinction between runtime reads and management API updates.

## Management APIs

Use a platform user Bearer token with `X-AgentScope-Tenant` and `X-AgentScope-Namespace`; prepare variables as in the [API identity setup](/v2/en/service/create-managed-agent#api-setup). Listings are filtered to inspectable resources. Reads require inspect, mutations require edit, and creation requires namespace resource creation rights.

| Operation | API | Parameters and response |
| --- | --- | --- |
| List | `GET /api/environments` | Returns an array; optional `limit` (1–500), `offset` (nonnegative, requires limit); total in `X-Total-Count` |
| Create | `POST /api/environments` | `name`, `type`, optional `config`; returns the Environment and a one-time `apiKey` |
| Read | `GET /api/environments/{id}` | `id`, `name`, `type`, `config`, `ownerId`, `archivedAt`, timestamps; no key |
| Update | `PATCH /api/environments/{id}` | Optional `name`, `config`; config replaces the entire object, type is immutable |
| Archive | `POST /api/environments/{id}/archive` | Returns the Environment with `archivedAt`; removed from active listings |
| Rotate key | `POST /api/environments/{id}/rotate-key` | Returns a new `apiKey`; the old key stops working immediately |
| Delete | `DELETE /api/environments/{id}` | Returns 204 |

These mutations have no version condition. Read config and retain other required settings before replacing it. Archived resources cannot be patched. Check Agent and Session usage before archival or deletion; resource maintenance does not cancel running work.
