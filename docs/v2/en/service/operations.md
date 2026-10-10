---
title: "Operations, recovery, and troubleshooting"
zh_link: /v2/zh/service/operations
---

<Note>
The current release is `2.1.0-BETA1`, a prerelease. Validate your deployment before production use.
</Note>

A recoverable backup includes the database, workspaces, artifacts and the keys needed to decrypt stored credentials.

<span id="compose-operations"></span>

## Persist data

Compose uses three named volumes for PostgreSQL data, shared Workspaces, and Artifacts. Locate this project’s volumes with `docker volume ls` and include them in your backup policy. When backing up encrypted data, securely preserve the Vault master key from `.env` as well, because recovery still requires the original key.

If tools need business materials from the host, configure explicit directory mounts and give container user `65532:65532` the necessary access. Merely writing a host path in an Agent instruction does not make it available inside the container. First confirm which directory the selected Environment can actually access, then provide that location to the Agent.

## Change configuration or version

After editing `.env`, run the Compose startup command again so services use the new configuration. Check component status afterward to confirm that the updated installation still runs normally.

```bash
docker compose up -d --wait --wait-timeout 600
docker compose ps
```

To upgrade, edit `SERVICE_VERSION` in `.env`, pull the corresponding new images, and restart the services. Initialization preserves an existing `.env`, so rerunning `init-env.sh` does not perform the version change for you. Coordinate any secret change across components that use it. In particular, the Vault master key is needed to decrypt existing data and cannot be replaced like an ordinary login password.

The complete Compose installation uses standalone HTTP. To connect an External SDK that depends on ASDP, prepare its runtime transport according to [External integration](/v2/en/service/external-agent). See [production installation](/v2/en/service/kubernetes) for Kubernetes setup. Whatever deployment you use, rehearse [backup and recovery](/v2/en/service/operations) before upgrading.

## Stop, resume and diagnose

To stop the platform temporarily, run `docker compose down`. It stops services while retaining their data volumes, so the startup command can later resume the installation with its existing data. Do not add `-v` for an ordinary shutdown, because that option also deletes the volumes.

If startup fails, use `docker compose ps -a` to identify the component that did not start, then inspect `docker compose logs --tail=100` to determine whether the problem involves image pulling, database connectivity, or component startup. If the host port is occupied, change `GATEWAY_PORT` in `.env`. If this also changes the public address, update `BUILDER_OAUTH_PUBLIC_URL` accordingly, then recreate the containers.

## Docker backup

Schedule a maintenance window. Stop the four application components while leaving PostgreSQL running:

```bash
docker compose stop gateway scheduler data control
mkdir -p backup
chmod 700 backup
docker compose exec -T db pg_dump -U agentscope -d agentscope -Fc > backup/database.dump
```

Use your volume-backup tool to snapshot the `workspaces` and `artifacts` volumes. Save `.env`, `SERVICE_VERSION`, image digests and snapshot time. Store backups on protected persistent storage outside the installation directory. Confirm that database and file snapshots belong to the same maintenance window before restarting applications.

```bash
docker compose up -d --wait --wait-timeout 600
```

Kubernetes also requires database backups and PVC snapshots. Follow the storage provider's snapshot procedure and preserve a protected copy of the configuration Secret.

## Upgrade

Read the release's migration and compatibility notes and rehearse against a test copy first. After backing up, update `SERVICE_VERSION` in Docker's `.env`, pull images and recreate containers. For Helm, upgrade using the specific Chart version.

Go runs product and runtime migrations on startup; Java currently updates schema through Hibernate. Rolling back images does not establish that an older version can read a newer schema.

## Recover

Stop application writes before data recovery. Restore into an independent empty database, restore matching workspace and artifact snapshots and the original Vault key, then start the component versions corresponding to the backup. Do not point `pg_restore --clean` at a live application database.

For a newly created recovery database:

```bash
pg_restore --no-owner --no-acl --dbname="$RESTORE_DATABASE_URL" backup/database.dump
```

After restoring into a new database on the same PostgreSQL instance, set `POSTGRES_DB` in Compose `.env` to its name and start with the original keys and matching file snapshots. The database container initializes databases only when its data directory is empty; create a recovery database explicitly on an existing instance.

## Verify recovery

Check administrator login, existing Agents and Session history, workspace files, Vault decryption, runtime connectivity and a new small task. Task and Issue acceptance state should match the backup point.

A backup is qualified only when database, files and keys recover together. Plan maintenance windows for this single-replica installation.

## Check recovered services through APIs

Read existing resources before submitting a clearly new test task:

| Check | API |
| --- | --- |
| Agents and bindings | `GET /api/v1/agents`, `GET /api/v1/agents/{id}/bindings` |
| Session configuration | `GET /api/v1/agent-sessions/{sessionId}` and its frozen target |
| Existing business results and UI | `GET /api/v1/agent-sessions/{sessionId}/turns/{turnId}`, `GET .../{id}/snapshot` |
| Orchestration and actual attempts | `GET /api/v1/orchestration-runs/{id}/graph`, `GET /api/v1/execution-attempts/{id}` |
| Notification and automation | Inspect original Webhook/Automation delivery records and deduplication state |

Use the original invocation ownership credentials or authorized platform identity. Recover Turn data, native Managed logs, workspace files, and encryption keys together. Expired event cursors require a fresh snapshot, not resubmission of completed work. See [API reference](/v2/en/service/api-reference).

## Reopen service after recovery

Keep scheduled rules and external traffic controlled while verifying login, history, files and credentials with test work. Confirm Runtime Hosts reconnect before restoring schedules and application traffic. Restoring a snapshot does not undo external messages or writes made after it; reconcile idempotency records and unfinished work before rerunning.

## Use fixed cases for upgrade regression

Before upgrading, retain the fixed request, source versions, and acceptance results from the [CRM proposal case](/v2/en/service/cases/in-product-delivery). Repeat the call in an isolated restored environment. Model wording may differ; compare these facts and persistent records:

| Check | Evidence |
| --- | --- |
| Source recovery | All three inline sources and versions match; separately verify reads if production uses Memory |
| File recovery | Previous Artifacts download and match the recorded content |
| New work | A new Turn / Run completes with correct sources and no unsupported capability promises |
| History | Previous Turns, events, artifacts, and application acceptance records remain readable |
| Host and scheduling, if used | Run code repair or recurring research against test targets and inspect linked records |

Record versions, backup batch, inputs, execution IDs, and differences. Validate credentials through the integrations that use them; the knowledge-only case has no external credential and cannot establish that Vault decryption works.

<span id="troubleshooting"></span>

## Collect component logs

```bash
docker compose logs --tail=200 control data scheduler gateway
kubectl -n agentscope logs deployment/service-agentscope-control --tail=200
kubectl -n agentscope describe pod POD_NAME
```

Gateway health does not verify model or tool execution. Inspect Dataplane for Managed Session failures, Scheduler for channel/Worker scheduling, and Control for product Automations, resources, accounts and orchestration. Hosted provider failures also need the machine's daemon logs.

## Restarting did not reset the administrator password

This is expected. `CONTROL_PLANE_BOOTSTRAP_PASSWORD` applies only to an empty account table. Change existing passwords through Profile or administrator account management rather than regenerating `.env`.

## Vault decryption fails

Check that recovery preserved the original `BUILDER_VAULT_MASTER_KEY` and that all components use the same value. Restore matching configuration and data before trying to replace keys.

## Work arrived but no final result

Inspect the Run, Node and latest Attempt from Issue Executions, not just the last message. Check dependencies, approvals or signals for waiting, missing input for blocked, and errors/partial artifacts for failed. Inbox Request changes does not start execution. Verify task reporting for External adapters and provider exit/reporting for Hosted work.

## Repeated Webhook or Session requests

Query the existing Delivery/Turn first. Reuse the same key and content for the same logical request; assign a new key only to new work. Check trigger event filters, authentication and schemas. After SSE disconnects, query the returned statusUrl before resubmitting.

## Diagnose unified service API calls

Save the Session ID, Turn ID, idempotency key, and error code. Read `/api/v1/agent-sessions/{sessionId}/turns/{turnId}` for task status, then that Turn’s `/snapshot` and `/capabilities` for saved results and available commands.

| Symptom | Check |
| --- | --- |
| No key after Session creation | Create a credential under an Application with explicit targets and scopes |
| 401/403 | Credential target grants, Application status/scopes, and designated approver identity |
| 409 | Resource version, reused key with changed input, incompatible target, or active Conversation conflict |
| Accepted input has no visible effect | Query the command receipt; acceptance does not mean model consumption |
| SSE 410/cursor_expired | Replace the UI from a fresh snapshot and resume after as_of without resubmitting work |
| A member finished but the call is active | Inspect Turn and steps, not just member output |
| Resume unavailable | Respect available_commands; backend support differs and public checkpoint restore is unavailable |

`/api/v1/events` is a WebSocket refresh notification, not durable Turn SSE. See [Unified service API](/v2/en/service/service-api).

## Agent API and SSE

The following applies to native Managed sessions.

Keep the session ID, turn ID, latest event ID, HTTP status and sanitized error. Distinguish the page connection, task execution and context recovery:

| Symptom | Action |
| --- | --- |
| Refresh shows only a suffix or loses tools produced while away | Render snapshot.items/tools first, then stream after as_of; a cursor alone cannot rebuild UI state |
| Stream closes and task status is unclear | Read turns/{turn} or snapshot; disconnect neither cancels nor calls for a new turn |
| Task stays running after run.ended / item.completed | Wait for the target turn outcome; an attempt, message or tool is not the whole task |
| 400 / 409 cursor error | Verify session scope and reload snapshot; never parse or increment cursors yourself |
| Resource pagination returns 410 | Restart from its first page; resource-page cursors are not SSE cursors |
| Answer submitted but the tool does not continue | Read required_actions and GET turns/{turn}/actions; accepted is receipt, rejected requires checking reason and pending |
| Steer returns 409 | The task may have ended or closed input; reread status, and use a new turn for a separate question |
| Checkpoint restore returns 409 | Resolve open tasks, actions, pending inputs and unknown tool outcomes; restoring does not undo external operations |
| Cost is incomplete or budget blocks execution | Inspect unpriced calls, usage and pricing in usage/budget; adjust limits and explicitly resume as task state permits |
| No webhook received | Check allowed hosts, registration time, event filters and deliveries; fix the receiver before retrying a paused webhook |
| Heartbeats but no text | Check task, tool and model state; deltas may be unavailable. If content arrives in bursts, inspect proxy buffering |

See [Sessions and tasks](/v2/en/service/session-event-log) and [SSE replay](/v2/en/service/sse-events). Applications retain Sessions, Turns, and cursors without managing internal runtime identity mappings.

## Updating Control Plane naming

The Go component lives in `agentscope-service/service-controlplane` and its server binary is `service-controlplane`. When updating an existing installation, update build paths, startup commands, deployment manifests, and environment variables together. Control Plane configuration uses the `CONTROL_PLANE_` prefix; HTTP clients use `CONTROL_PLANE_HTTP`, while the CLI and Runtime Host use `CONTROL_PLANE_URL`. The CLI is `as` and the Runtime Host executable is `agentscope-runtime-host`. Use the environment templates shipped with the same Service version to avoid combining old configuration with new binaries.

The Java integration is `agentscope-extensions-controlplane`, with `io.agentscope.extensions.controlplane.ControlPlane` and `ControlPlaneConfig`. The Python distribution is `agentscope-service-sdk`, imported as `agentscope_service`; the DSH plugin is `@agentscope-service/dsh-controlplane`. Update dependencies and imports before redeploying connected agents. If you customize journal paths, Helm resource names, or Console preferences, migrate those local settings as part of the upgrade. Database schemas and the ASDP `agentscope.protocol.v1` wire contract keep their existing identities.
