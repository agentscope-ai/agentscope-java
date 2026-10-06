---
title: Connect a Runtime Host
zh_link: /v2/zh/service/runtime-host
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A Runtime Host runs on a computer or server with a Coding Agent provider installed. The control plane dispatches and records work; the Host executes it with the local provider.

## Install

Download `agentscope-cli-VERSION-OS-ARCH.tar.gz` for the operating system and CPU architecture from the same Service release. Verify its checksum and extract it. The archive contains `agentscope`, the alias `aistioctl`, and `aistio-runtime-host`. Put all three executables in the same directory on PATH.

Choose Linux/macOS and amd64/arm64 according to the release manifest and the machine you will connect. Install, authenticate and verify the provider separately.

## Extract onto PATH

Check the platform with `uname -sm`; package names use `linux`/`darwin` and `amd64`/`arm64`. Download the matching [Release](https://github.com/agentscope-ai/agentscope-java/releases) archive and replace the filename below:

```bash
mkdir -p agentscope-cli "$HOME/.local/bin"
tar -xzf agentscope-cli-VERSION-OS-ARCH.tar.gz -C agentscope-cli
install -m 0755 agentscope-cli/agentscope agentscope-cli/aistioctl agentscope-cli/aistio-runtime-host "$HOME/.local/bin/"
export PATH="$HOME/.local/bin:$PATH"
```

Persist the PATH setting in your shell configuration for later terminals.

## Connect

```bash
agentscope connect https://agentscope.example.com
agentscope runtime status
agentscope runtime probe
```

Follow the CLI's login or enrollment prompts. Connection saves local configuration and starts the daemon. Normal operation does not require repeatedly supplying a shared internal service token.

## Daily operation

```bash
agentscope runtime logs
agentscope runtime stop
agentscope runtime start
```

Configuration and state default to `~/.agentscope/runtime-host/`. Preserve the Host identity and state files to avoid accidentally registering an existing computer as a new instance. Do not distribute this directory as a public configuration example.

After connecting, verify Host and provider availability through the management API, bind a Hosted Agent, and dispatch a small task. Diagnose failures with both Task Attempt records and Host logs.

A Runtime Host is distinct from a Managed Agent Hands Worker. See [Environments](/v2/en/service/environments).

## Connect an unattended server

An authorized operator creates a short-lived enrollment token:

```bash
agentscope runtime enrollment-token create
```

On the target machine, supply it through `AGENTSCOPE_ENROLLMENT_TOKEN` and run connect. The service exchanges it for a credential bound to Host identity and scope. Keep enrollment tokens and `config.json` out of shared scripts.

## CLI reference

| Command | Purpose |
| --- | --- |
| `agentscope connect URL` | Authenticate/register, save configuration and start the daemon |
| `agentscope runtime status` | Inspect state |
| `agentscope runtime probe` | Check provider availability |
| `agentscope runtime logs -f` | Follow logs |
| `agentscope runtime restart` | Restart the daemon |
| `agentscope runtime stop` / `start` | Stop or start |
| `agentscope connect --help` | Inspect advanced flags in the installed version |

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

Pass `enrollmentToken` securely to the target machine and run `agentscope connect`. Business applications do not need to call Host register, heartbeat, or claim themselves.

### Runtime Host protocol

The daemon calls these APIs with a Host-scoped Bearer credential. Direct integration is for custom Host implementations; business task assignment uses Issue or Endpoint APIs.

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

State operations return `attempt`. For events, inspect `accepted` and stop submitting after the Attempt is sealed. A Host checkpoint is provider recovery material, not a unified Agent API checkpoint restore operation. See [Hosted recovery](/v2/en/service/hosted-agent-execution) for the distinction.
