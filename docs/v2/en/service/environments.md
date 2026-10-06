---
title: "Environments: execution locations"
zh_link: /v2/zh/service/environments
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

An Environment defines where Managed Agents execute file, Shell, and other tools and is managed through `/api/environments`. It is separate from a definition Workspace and a Hosted Agent's Runtime Host. Its console entry is **Resources → Environments**; see [Console](/v2/en/service/console/index) for graphical operations.

## Management APIs

Use a platform user Bearer token with `X-AgentScope-Tenant` and `X-AgentScope-Namespace`; prepare variables as in the [API quickstart](/v2/en/service/first-session). Listings are filtered to inspectable resources. Reads require inspect, mutations require edit, and creation requires namespace resource creation rights.

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

For example, create an environment for a self-hosted tool worker:

```bash
ENVIRONMENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/environments" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"name":"Report worker","type":"self_hosted","config":{}}')
ENVIRONMENT_ID=$(printf '%s' "$ENVIRONMENT_JSON" | jq -er '.id')
ENVIRONMENT_KEY=$(printf '%s' "$ENVIRONMENT_JSON" | jq -er '.apiKey')
```

Save the ID and key, then connect a Worker using the commands later on this page. For other types, follow the configuration below. Creating local requires deployment permission for local execution; otherwise it returns 403.

## Interface tour

<Frame caption="Current console UI with fixed demonstration data.">
  <img src="/imgs/service/environments.png" alt="Local and self_hosted environment examples" />
</Frame>

Choose an environment according to where execution should happen. **Local development** represents local execution, while **Research worker** is an example self_hosted configuration. Creating the record still requires starting and connecting its Worker.

## Choose a type

| Type | Execution model |
| --- | --- |
| local | Runs with the Dataplane; requires Local to be enabled by an administrator |
| sandbox | Isolated Shell/filesystem execution through E2B |
| remote | Shared BaseStore filesystem without Shell execution |
| self_hosted | Execution supplied by a Worker you operate |

In Docker, Local means inside the Dataplane container, not arbitrary access to the host filesystem. Choose a backend according to production isolation and networking requirements.

## Bind and configure

Set `defaultEnvironmentId` through the [Agent definition API](/v2/en/service/managed-agent-configuration). Read the full definition first, retain other fields, and supply its current version when updating. Creating a Managed session with `environmentId` overrides its environment; omission uses the Agent default.

Credentials and capabilities must match the backend; Runtime Host enrollment credentials are not Worker credentials. Create a new Session and verify the connection, working directory, and permissions with a read-only file operation, then check writes and commands. Managed model inference remains in the Dataplane even with self_hosted tool execution.

`config.memoryAccess` sets mount access by Memory Store ID, for example `{"memoryAccess":{"STORE_ID":"read_only"}}`. Values are `read_only` or `read_write`, defaulting to read-write for unconfigured stores. This controls runtime access to bound stores; it does not bind a Store. See [Memory](/v2/en/service/memory).

## Self-hosted execution

A self-hosted Worker connects using an Environment API key. Save the key when created and supply it through the Worker's connection configuration. Check online status and a real tool call after connection. Workers execute Managed tools; Runtime Hosts run Coding Agent providers. They are not interchangeable processes.

## Diagnose tool failures

Check Environment availability, network and authentication, directory mounts, required executables and permissions. A successful model reply does not prove that file tools work. Verify configuration changes with new work and update every consumer after key rotation.

Next: [Managed Agents](/v2/en/service/managed-agent) · [Configuration](/v2/en/service/configuration).

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
