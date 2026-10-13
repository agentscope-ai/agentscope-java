---
title: "Runtime Host setup and operations"
zh_link: /v2/zh/service/runtime-host
---

<Note>
This guide uses the `2.1.0-BETA1` prerelease.
</Note>

A Runtime Host runs on a computer or server with a Coding Agent provider installed. The control plane dispatches and records work; the Host executes it with the local provider.

## Install with Go

Install [Go](https://go.dev/doc/install) 1.26 or newer on the target Linux or macOS host. Install both commands from the same published version; no repository checkout or Service deployment is required:

```bash
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/as@v2.1.0-BETA1
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/agentscope-runtime-host@v2.1.0-BETA1
```

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

Persist the PATH setting in your shell configuration for later terminals. `as version` should report `2.1.0-BETA1`. The `/v2` module path and `@v2.1.0-BETA1` select the published prerelease.

Install and authenticate a supported Coding Agent provider separately on this host, and verify that it can run a request. The CLI and Runtime Host connect to an existing Service; they do not deploy the platform. To update, stop the Runtime Host, rerun both `go install` commands with the same new version, and restart it while preserving its state directory.

## Connect

```bash
as connect https://agentscope.example.com
as runtime status
as runtime probe
```

Follow the CLI's login or enrollment prompts. Connection saves local configuration and starts the daemon. Normal operation does not require repeatedly supplying a shared internal service token.

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

Single-scope deployments use the configured scope. Multi-scope deployments require `tenant` and `namespace` when issuing an enrollment token. Exchange cannot override the token's scope. Draining prepares a Host for maintenance; use the [AgentTask API](/v2/en/service/issues) to cancel active work.

For example, issue an enrollment token from a terminal with management access configured:

```bash
curl -sS "$SERVICE_URL/api/v1/runtime-host-enrollment-tokens" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"default","namespace":"default"}'
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
| `/preparing` | None | Report preparation |
| `/running` | Optional `providerSessionId`, `workspaceKey` | Report provider execution start |
| `/checkpoint` | `checkpoint`, optional `providerSessionId` | Save provider recovery information |
| `/events` | Positive `ordinal`, `provider`, `eventType`; optional `providerSessionId`, `raw` | Submit an event; `raw` is limited to 256 KiB |
| `/complete` | Optional `result`, `checkpoint` | Report completion |
| `/fail` | `failureCode`, `failureMessage`, optional `checkpoint` | Report failure |
| `/cancelled` | None | Confirm cancellation |

State operations return `attempt`. For events, inspect `accepted` and stop submitting after the Attempt is sealed. A Host checkpoint is provider recovery material, not a unified Agent API checkpoint restore operation. See [Hosted recovery](/v2/en/service/runtime-host#hosted-agent-execution) for the distinction.

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
| Request cancellation or retry | `POST /api/v1/agent-tasks/{taskId}/cancel`, `/retry` | Submit version fields as described in [Issue API](/v2/en/service/issues); then inspect final state |
| Read Workflow progress | `GET /api/v1/orchestration-runs/{runId}/graph`, `/events` | Node graph and run events reflecting execution results |
| Restore an application view | `GET /api/v1/agent-sessions/{sessionId}/turns/{turnId}/snapshot`, then `/events/stream` | Resume SSE from the snapshot cursor with the invocation credential |

The Host protocol's `/checkpoint` operation saves `providerSessionId` and checkpoint data for supported adapter recovery. It is not an application API for restoring any backend from an arbitrary checkpoint. Unified invocations currently report `checkpoint_restore: false`. Hosted conversations support cancellation; do not assume Managed input, approval, or resume features are available. Read `available_commands` from `/api/v1/agent-sessions/{sessionId}/turns/{turnId}/capabilities` before offering interactions.

SSE reconnection restores recorded output. It does not rerun tools or recover a Host process. Upload durable deliverables as Artifacts; Host files, provider sessions, and framework state retain their own lifecycles. See [Runtime Host protocol](/v2/en/service/runtime-host#runtime-host-protocol) for API parameters.
