---
title: "Runtime Host setup and operations"
zh_link: /v2/zh/service/runtime-host
---

<Note>
Local development requires the current source; published production commands use `2.1.0-BETA1`.
</Note>

A Runtime Host runs on a computer or server with a Coding Agent provider installed. The control plane dispatches and records work; the Host executes it with the local provider.

## Install with Go

Prepare Go 1.26 or newer. Install CLI and Runtime Host from the current source containing local development mode. Run from the repository root:

```bash
cd agentscope-service/service-controlplane
go install ./cmd/as ./cmd/agentscope-runtime-host
```

The published `v2.1.0-BETA1` CLI does not automatically enter the local development identity. To connect to production, install the published version using the [production guide](/v2/en/service/kubernetes#production-cli-install).

Go builds the binaries for the current machine and writes them to `GOBIN`, or `$(go env GOPATH)/bin` when `GOBIN` is unset. Add that directory to PATH and check both commands:

```bash
AS_CLI_BIN_DIR="$(go env GOBIN)"
if [ -z "$AS_CLI_BIN_DIR" ]; then
  AS_CLI_BIN_DIR="$(go env GOPATH)/bin"
fi
export PATH="$AS_CLI_BIN_DIR:$PATH"
as version
agentscope-runtime-host -help
```

Persist the PATH setting in your shell configuration for later terminals.

Install and authenticate a supported Coding Agent provider separately on this host, and verify that it can run a request. The CLI and Runtime Host connect to an existing Service; they do not deploy the platform. To update, stop the Runtime Host, reinstall both commands from the same source or release version, and restart it while preserving its state directory.

## Connect

```bash
as connect http://localhost:18080
as runtime status
as runtime probe
```

In local mode, the CLI automatically obtains the development identity, saves configuration, and starts the daemon. Production login and enrollment are described in the [production guide](/v2/en/service/kubernetes#production-api-access).

## Daily operation

```bash
as runtime logs
as runtime stop
as runtime start
```

Configuration and state default to `~/.agentscope/runtime-host/`. Preserve the Host identity and state files to avoid accidentally registering an existing computer as a new instance. Do not distribute this directory as a public configuration example.

After connecting, verify Host and provider availability through the management API, bind a Hosted Agent, and dispatch a small task. Diagnose failures with both Task Attempt records and Host logs.

A Runtime Host is distinct from a Managed Agent Hands Worker. See [Environments](/v2/en/service/environments).

## Connect an unattended server

An authorized operator creates a short-lived enrollment token:

```bash
as runtime enrollment-token create
```

On the target machine, supply it through `AGENTSCOPE_ENROLLMENT_TOKEN` and run connect. The service exchanges it for a credential bound to Host identity and scope. Keep enrollment tokens and `config.json` out of shared scripts.

## CLI reference

| Command | Purpose |
| --- | --- |
| `as connect URL` | Authenticate/register, save configuration and start the daemon |
| `as runtime status` | Inspect state |
| `as runtime probe` | Check provider availability |
| `as runtime logs -f` | Follow logs |
| `as runtime restart` | Restart the daemon |
| `as runtime stop` / `start` | Stop or start |
| `as connect --help` | Inspect advanced flags in the installed version |

The state directory holds connection identity in `config.json`, stable Host identity in `state/host.id`, diagnostics in `daemon.log` and task directories in `workspaces/`. Check active tasks before updating paired CLI binaries, restart and preserve these files.

## Host enrollment and management APIs

The CLI uses these Host APIs. Platform account credentials authorize enrollment and management; enrollment tokens perform the initial exchange; `runtimeToken` authenticates only the associated Host's runtime protocol.

| Method and path | Request/query fields | Response |
| --- | --- | --- |
| `POST /api/v1/runtime-host-enrollment-tokens` | Platform Bearer; JSON `tenant`, `namespace` | 201; `enrollmentToken`, scope, `expiresAt` |
| `POST /api/v1/runtime-host-enrollments/exchange` | Enrollment Bearer; JSON `hostKey`, at most 200 characters | 201; `runtimeToken`, `hostKey`, scope, `expiresAt`; scope comes from the token |
| `POST /api/v1/runtime-host-enrollments` | Platform Bearer; `hostKey`, `tenant`, `namespace` | Issues a Host-scoped `runtimeToken` directly |
| `GET /api/v1/runtime-hosts` | Platform Bearer; query `tenant`, `namespace`; optional `poolName`, `state` | `items` with `id`, `hostKey`, `state`, `capacity`, `active`, `lastSeenAt`, `capabilities` |
| `GET /api/v1/runtime-hosts/{hostId}` | Platform Bearer; Host UUID | `host` |
| `PATCH /api/v1/runtime-hosts/{hostId}/capacity` | Platform Bearer; `capacity` from 1–50, `expectedCapacity` with current value | Updated `host`; reread after a concurrency conflict |
| `POST /api/v1/runtime-hosts/{hostId}/drain` | Platform Bearer | `host`; stops taking new work |
| `POST /api/v1/runtime-hosts/{hostId}/resume` | Platform Bearer | `host`; restores scheduling availability |

Single-scope deployments use the configured scope. Multi-scope deployments require `tenant` and `namespace` when issuing an enrollment token. Exchange cannot override the token's scope. Draining prepares a Host for maintenance; use the [AgentTask API](/v2/en/service/issues#curl-management) to cancel active work.

For example, issue an enrollment token from a terminal with management access configured:

```bash
set -euo pipefail

ENROLLMENT_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/runtime-host-enrollment-tokens" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE"
}
JSON
)
ENROLLMENT_TOKEN=$(jq -er '.enrollmentToken' <<< "$ENROLLMENT_JSON")
```

Pass `enrollmentToken` securely to the target machine and run `as connect`. Business applications do not need to call Host register, heartbeat, or claim themselves.

### Runtime Host protocol

The daemon calls these APIs with a Host-scoped Bearer credential. Direct integration is for custom Host implementations; business task assignment uses Issue or Session APIs.

| POST path | Key fields | Behavior |
| --- | --- | --- |
| `/api/v1/runtime-hosts/register` | `hostKey`, `poolName`, scope, `capacity`; optional `daemonVersion`, `os`, `arch`, `labels`, `capabilities` | Returns `host` and `heartbeatIntervalSeconds`; retain `host.id` and `host.leaseGeneration` |
| `/api/v1/runtime-hosts/{hostId}/heartbeat` | `generation`, `active`, optional `capabilities` | Updates liveness/capabilities; returns `host` |
| `/api/v1/runtime-hosts/{hostId}/state` | `generation`, `state` | Updates state; returns `host` |
| `/api/v1/runtime-hosts/{hostId}/execution-attempts/claim` | `tenant`, `namespace`, `runtimePoolName`, `generation`, `leaseOwner`, `leaseToken`; optional `leaseSeconds` | 204 when no work exists; otherwise `task`, `context`, `attempt`, `taskToken`, `attemptToken`, `runtimeProfile`, `executionOverrides`, `definition` |

After claiming work, paths below are relative to `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}`. In addition to Host Bearer authentication, send the claim response's `attemptToken` as `X-Execution-Attempt-Token`. JSON must include the current `leaseToken` and `fencingToken`; the daemon also sends `generation` and `leaseOwner`. Never reuse another execution's values.

| POST suffix | Additional fields | Purpose |
| --- | --- | --- |
| `/renew` | Optional `leaseSeconds`, default 30 | Renew the lease and observe cancellation/terminal state |
| `/observe` | None | Read the current Attempt without extending its lease or execution grant; the original lease token and fence are required |
| `/preparing` | None | Report preparation |
| `/running` | Optional `providerSessionId`, `workspaceKey` | Report provider execution start |
| `/checkpoint` | `checkpoint`, optional `providerSessionId` | Save provider recovery information |
| `/events` | Positive `ordinal`, `provider`, `eventType`; optional `providerSessionId`, `raw` | Submit an event; `raw` is limited to 256 KiB |
| `/complete` | Optional `result`, `checkpoint` | Report completion |
| `/fail` | `failureCode`, `failureMessage`, optional `checkpoint` | Report failure |
| `/cancelled` | None | Confirm cancellation |
| `/physical-stop` | None | For Portal-owned work, seal events after the provider has stopped and its durable event outbox has drained |

State operations return `attempt`. For events, inspect `accepted` and stop submitting after the Attempt is sealed. A Host checkpoint is provider recovery material, not a unified Agent API checkpoint restore operation. See [Hosted recovery](/v2/en/service/runtime-host#hosted-agent-execution) for the distinction.

In the new Portal execution path, `/complete`, `/fail` and `/cancelled` record the native outcome. The original Host then uploads any remaining committed events and calls `/physical-stop` with the same Attempt credential and fence. Portal publishes the Turn terminal state only after this proof and the final output and usage have been collected. The Host journal retains this operation across restarts and retries an unknown acknowledgment with the same identity. Permission expiry rejects new execution and renewal; it does not prevent the original Host from draining committed results and proving that execution stopped.

When renewal is rejected, the daemon uses `/observe` to distinguish cancellation from an expired grant. Observation does not authorize execution. A cancellation stops the provider and reports `/cancelled` before the final stop proof. Keep the Attempt credential until the stop acknowledgment succeeds.

Portal-owned Hosted tool confirmations use task-scoped `POST /api/v1/agent-tasks/{taskId}/runtime-approvals`, `GET /{approvalId}/decision` and `POST /{approvalId}/ack` under that path. Portal preserves the original physical task credential and sends the callbacks to the private Go runtime. Waiting renews only the physical lease; it does not extend the root execution grant. A human answers the standard `required_action.created` through the public Turn actions API. Durable acceptance fences the previous activation, refreshes invocation and approver permissions, and records the original human decision. The runtime releases the decision only with its matching current grant. `required_action.resolved` and an applied command receipt follow the Host acknowledgment; acceptance alone is not delivery.

Provider events are committed to individual local journal files before delivery. Each pass delivers at most 64 events and processes at most 8 pending Attempts. The journal admits at most 256 pending Attempts, 65,536 events and 256 MiB of events per Attempt, with at most 1 MiB per encoded event. Delivery failures retain the records and use bounded backoff; a full journal stops new claims. Cleanup also runs in finite batches. This recovers committed delivery operations across restart; it does not guarantee recovery of an interrupted provider process.

For Portal-owned Hosted tasks, collaboration MCP uses `POST /mcp/collaboration` with the original task bearer. The signed task identity includes the Attempt generation, physical fence and Host ID; the runtime verifies the root grant and current physical lease before allowing work. Portal adds its own internal transport credential, which is not exposed to the provider. The daemon acquires an OS lock on its state directory before registering; a second daemon using that directory is rejected. Keep the lock file in place. Process exit releases the lock automatically.

<span id="hosted-agent-execution"></span>

## Registration and task selection

Host registers stable identity, scope, pool, provider descriptors and capacity. Agent bindings, capability requirements and runtime policy determine an Attempt. Host claims eligible work and maintains its lease. Host connectivity, provider discovery and Agent dispatch readiness are separate checks.

Runtime Profiles select provider parameters. Pools supply eligible Hosts. Host capacity and higher-level scheduling policies jointly constrain concurrency.

## Preparation and execution

Host prepares a task directory, translates supported platform instructions/capability files into provider formats and starts the provider in that directory. It does not automatically use the local checkout you are editing; specify repository, input material and branch during task preparation.

Task-scoped credentials and context are supplied through environment and MCP/CLI integration. Executors can read work, post comments and upload Artifacts. A file left on Host disk is not automatically accessible to collaborators; upload shared deliverables as Artifacts.

## Events, approval and recovery

Adapters convert provider events into execution records. Supported platform approval flows forward tool requests and await a decision. Other providers use their native permission mechanisms.

Host preserves journals, provider session identifiers and checkpoints for supported recovery paths. Keep state and Host identity across restarts. Resume support does not guarantee every interrupted execution can recover: the provider session must still exist and be accessible. The OpenClaw adapter currently has no Session resume.

## Retry and cancellation

Cancellation propagates through execution; check Attempt terminal state and provider process termination. A retry creates a new Attempt. Reuse a provider session only when recovery conditions hold. Cross-backend fresh fallback reconstructs context from persistent Issues, comments and Artifacts, without migrating process memory.

Use `as runtime logs -f` with Task/Attempt diagnostics. If no work is claimed, check scope, pool, bindings, capacity and required capabilities. If claimed work fails, check provider login, parameters, task directory and tool dependencies.

Related: [installation](/v2/en/service/runtime-host), [providers](/v2/en/service/hosted-agent-configuration#hosted-agent-providers) and [Team collaboration](/v2/en/service/create-team#team-collaboration).

## Track and control work through the API

Platform accounts assign work through Issues or Sessions. Host credentials are for daemon claims, renewals, and reports. Business callers do not handle `leaseToken` values or provider processes.

| Scenario | API and parameters | Response/purpose |
| --- | --- | --- |
| Read AgentTask | `GET /api/v1/agent-tasks/{taskId}` | Task state and execution references |
| List physical attempts | `GET /api/v1/execution-attempts?tenant=...&namespace=...&taskId=...`; optional `state`, `limit` | `attempts`; retries have separate records |
| Read an Attempt | `GET /api/v1/execution-attempts/{attemptId}` | `attempt`, including backend, Host, lease, failure, and recovery data |
| Request cancellation or retry | `POST /api/v1/agent-tasks/{taskId}/cancel`, `/retry` | Submit version fields as described in [Issue API](/v2/en/service/issues#curl-management); then inspect final state |
| Read Workflow progress | `GET /api/v1/orchestration-runs/{runId}/graph`, `/events` | Node graph and run events reflecting execution results |
| Restore an application view | `GET /api/v1/agent-sessions/{sessionId}/turns/{turnId}/snapshot`, then `/events/stream` | Resume SSE from the snapshot cursor with the invocation credential |

The Host protocol's `/checkpoint` operation saves `providerSessionId` and checkpoint data for supported adapter recovery. It is not an application API for restoring any backend from an arbitrary checkpoint. Unified invocations currently report `checkpoint_restore: false`. Hosted conversations support cancellation and pending root tool confirmations through Turn actions. Managed input and explicit resume features are not available on Hosted. Read `available_commands` from `/api/v1/agent-sessions/{sessionId}/turns/{turnId}/capabilities` before offering interactions.

SSE reconnection restores recorded output. It does not rerun tools or recover a Host process. Upload durable deliverables as Artifacts; Host files, provider sessions, and framework state retain their own lifecycles. See [Runtime Host protocol](/v2/en/service/runtime-host#runtime-host-protocol) for API parameters.

<span id="curl-management"></span>

## Host management examples

Use the platform identity and scope variables from [API setup](/v2/en/service/create-managed-agent#api-setup). Use resource IDs returned by creation or lookup.

After issuing an enrollment token, the target host exchanges that short-lived token for its runtime token. Use a stable `HOST_KEY` unique to that host. Do not substitute the platform token for the enrollment token. `as connect` normally handles this exchange.

```bash
HOST_KEY="report-worker-1"
```

```bash
ENROLLED_HOST=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/runtime-host-enrollments/exchange" \
    -H "Authorization: Bearer $ENROLLMENT_TOKEN" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "hostKey": "$HOST_KEY"
}
JSON
)
RUNTIME_TOKEN=$(jq -er '.runtimeToken' <<< "$ENROLLED_HOST")
```

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/runtime-hosts" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE"
```

Choose HOST_ID from the host list, then read its current capacity.

```bash
HOST_ID="HOST_ID_FROM_LIST"
```

```bash
HOST_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/runtime-hosts/$HOST_ID"
)
HOST_CAPACITY=$(jq -er '.host.capacity' <<< "$HOST_JSON")
```

```bash
curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/runtime-hosts/$HOST_ID/capacity" \
  -H "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "capacity": 2,
  "expectedCapacity": $HOST_CAPACITY
}
JSON
```

<Accordion title="Drain and resume a host">

Drain before maintenance to stop new claims, and wait for claimed work to finish. Resume after maintenance. Draining does not cancel claimed tasks.

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/runtime-hosts/$HOST_ID/drain"
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/runtime-hosts/$HOST_ID/resume"
```

</Accordion>


During the Session Portal refactoring, the Host machine protocol (register, heartbeat/state and Attempt claim/renew/checkpoint/events/terminal callbacks) can enter through Portal and reach the separate Go orchestration runtime. Portal preserves the Host credential and task fences; a service credential alone cannot identify a Host. User management operations such as capacity and enrollment remain on the control plane. This path currently has local process verification; other backend integration and deployment updates are still in progress.


The refactoring development role requires `BUILDER_VAULT_MASTER_KEY` when using
PostgreSQL. The key must have at least 32 bytes and remain stable across runtime
replicas. Portal bootstrap and Turn inbox payloads are encrypted at rest.
The shared configuration/inbox process path has been verified across Portal and
Go restarts. Hosted Job and Conversation execution from that inbox is connected;
other controls and backend integrations remain in progress. Local process checks
run the real daemon with a fake Codex app-server CLI, without external model calls.


Workflow interactions in the new Portal path, including nested subgraphs, use
`POST /api/v1/agent-sessions/{sessionId}/turns/{turnId}/actions`. Read pending
`required_actions` resources and submit either confirmations (`request_id`,
`allow`, optional `reason`) or signals (`request_id`, `value`) in one batch.
Signal values are limited to 64 KiB. Replies preserve the caller, refresh admission
before advancement, and become applied only after the native node consumes them.
Waiting for a human does not suspend independent runnable branches. Read Turn
capabilities before displaying these controls. Graph and step UUIDs keep repeated
node names distinct, with graph and parent references on events. The `steps`
resource in Session and Turn snapshots retains these graph and node states;
continue paginated reads with the same `snapshot_id` to keep a consistent view.
Agent tasks can submit a targeted signal through collaboration MCP:

```json
{"name":"run.signal","arguments":{"name":"report.ready","requestId":"PENDING_SIGNAL_NODE_UUID","idempotencyKey":"report-001","payload":{"artifactId":"ARTIFACT_ID"}}}
```

The task CLI equivalent is `as task run signal report.ready --request-id PENDING_SIGNAL_NODE_UUID --idempotency-key report-001 --file signal.json`.
Read the node UUID from the current run graph or pending actions. The courier verifies the physical
Task/Attempt lease, root execution grant and pending Signal node before creating a durable Portal
command. Its receipt distinguishes `queued` from native application; poll the Session command API
for `applied`. Retries with the same key and content return the original command ID. Changing the
content is a conflict. Task signals retain the original Turn caller's invocation permission and are
attributed to the Agent, so they cannot answer human confirmations. One pending signal command is
accepted per Turn; submit another after its receipt becomes `applied`. A durably accepted signal may
apply after the sender finishes, provided its Attempt/fence has not been replaced and the root Turn
still has a current grant.

Pause, explicit resume and independent Issue child runs are still being connected.

Hosted task and reporting tokens expire after 15 minutes. The daemon refreshes
them through `POST /api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/credentials`
before expiry and when replaying an expired journal credential. Refresh requires
the current Host credential and matching lease token and fence. It does not
extend the lease or restore a revoked execution permit. A stopped or revoked task
can refresh reporting authority to finish delivering its stop receipt; it receives
no new task authority. Providers use an attempt-local loopback credential relay
for collaboration MCP and the task CLI, so their static environment can use the
latest task token. The relay permits eight concurrent calls and bounded bodies,
and closes with the attempt. Host enrollment credentials retain their separate
expiry and rotation policy.

Public Session creation also accepts Hosted Team Jobs. The coordinator concludes
through its fenced collaboration MCP identity, after outstanding work nodes have
finished. The public Turn waits for every Hosted worker's physical stop receipt.
Local checks cover dynamic parallel nodes, completion and cancellation; full
delegation and the remaining graph controls are still being connected.

Portal resource snapshots return bounded pages in `resources`; continue each page with its
`snapshot_id` and `next_cursor`. The UI reads latest Turns with `order=desc` and may filter
`status=running`, so a long queue does not hide an earlier running Turn. Registry cursors
are bound to the order and status filter. Turn capabilities govern the selected Turn's
controls. Usage events carry authoritative `session_usage` and `turn_usage` summaries;
loading only part of model history does not reduce the reported totals. Budgets check
reported usage, and concurrent calls can exceed them with no fixed bound on that excess.

The UI replays live changes over a later historical page, including failed partial output
and deleted artifacts. Its display window is bounded to 4,096 live events, 8,192 indexed
records and 16 MiB of serialized text. When the window is full, refresh the view from a
new snapshot; this closes only the reader and does not stop execution.

Managed Turn capabilities also expose `tool_retry` while waiting for tools and
`workspace_recover` when the Session is ready. `resume` applies to failed or
interrupted Turns; pending tool or confirmation work uses its specific action.
The UI's tool execution panel reads a bounded task page and shows the current
authorization and Workspace generations. Unknown tool retry requires confirmation
of possible repeated side effects. Workspace reconnect requires verified idle state;
restore or discard requires verified provider absence and acceptance of lost file
changes. Workspace recovery does not retry the unknown tool. Check the persisted
command status, refresh generations, and then issue a separate tool retry if needed.

Hosted dispatch records keep scheduling identities, provider capabilities and profile/pool
versions in readable metadata. Frozen provider configuration, per-Agent overrides and
Workspace definitions are sealed with the runtime's stable vault key before the native
Task, Attempt and dispatch outbox are written. The authenticated Host claim resolves
the same frozen configuration; retry and node selection also open it under the original
Task identity. A runtime restart needs the same key. New Portal Hosted dispatch records
require this format; plaintext records from an earlier reconstruction are not accepted
as new Portal execution configuration. This does not encrypt every control-plane resource
definition or provider's local files.

In the current reconstruction, both Go service entry points default to
`--schema-mode=validate`. Run `--migrate-only` separately with the database owner
before starting ordinary replicas; the orchestration runtime migrates its runtime
schema, and the control plane can also migrate the enabled Product schema. The job
exits before HTTP and workers start, and does not bootstrap users. Runtime replicas
still need their stable internal credential, task signing secret and vault key.
See the repository's `agentscope-service/docs/session-portal-schema.md` for the Java
Portal/dataplane jobs and verified account separation. Compose/Helm now wire the
separate roles and migration jobs; complete runtime table ownership is still being implemented.

The separate Go runtime requires a Portal-owned execution contract before it
reconciles a Run, dispatches a Task, hands a physical Attempt to a Host, or renews
its execution authority. Legacy resource operations are still being migrated to
durable Portal intents; their old tasks do not become executable simply because
the new runtime reads the same store.

The private Go runtime image supports `--healthcheck`; it probes `/readyz` without
opening a database or starting workers. Execution ancestry rejects cycles and stops
after 256 Issue lookups, so a malformed parent chain cannot cause unlimited queries.

The daemon renews a Host machine credential when less than 24 hours remain. The
request passes through Gateway and Portal to the runtime, using the valid Host
bearer and current registration generation. Renewal preserves tenant, namespace
and Host key, and does not extend registration or Attempt leases or grant task
execution authority. Console enrollment retains the namespace authorized for the caller.

Renewed credentials are saved in `host-credential.json` under the locked state
directory and reused after restart with the same original enrollment credential.
The cache is bound to that credential and machine scope; a new enrollment takes
precedence. Keep the state directory private (the file uses mode 0600 on Unix and
inherits directory ACLs on Windows). A temporary renewal failure leaves the
current valid credential in use. If the daemon is offline until all its machine
credentials expire, enroll it again. Renewal does not revoke previously issued
tokens or rotate the service signing key.
