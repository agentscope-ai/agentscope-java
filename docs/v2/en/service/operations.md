---
title: Backup, upgrade and recovery
zh_link: /v2/zh/service/operations
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A recoverable backup includes the database, workspaces, artifacts and the keys needed to decrypt stored credentials.

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
| Published configuration | `GET /api/v1/endpoints/{id}`, `GET /api/v1/endpoints/{id}/releases` |
| Existing business results and UI | `GET /invoke/v1/invocations/{id}`, `GET .../{id}/snapshot` |
| Orchestration and actual attempts | `GET /api/v1/orchestration-runs/{id}/graph`, `GET /api/v1/execution-attempts/{id}` |
| Notification and automation | Inspect original Webhook/Automation delivery records and deduplication state |

Use the original invocation ownership credentials or authorized platform identity. Recover Invocation data, native Managed logs, workspace files, and encryption keys together. Expired event cursors require a fresh snapshot, not resubmission of completed work. See [API reference](/v2/en/service/api-reference).

## Reopen service after recovery

Keep scheduled rules and external traffic controlled while verifying login, history, files and credentials with test work. Confirm Runtime Hosts reconnect before restoring schedules and application traffic. Restoring a snapshot does not undo external messages or writes made after it; reconcile idempotency records and unfinished work before rerunning.

## Use fixed cases for upgrade regression

Before upgrading, retain the fixed request, source versions, and acceptance results from the [CRM proposal case](/v2/en/service/cases/in-product-delivery). Repeat the call in an isolated restored environment. Model wording may differ; compare these facts and persistent records:

| Check | Evidence |
| --- | --- |
| Source recovery | All three inline sources and versions match; separately verify reads if production uses Memory |
| File recovery | Previous Artifacts download and match the recorded content |
| New work | A new Invocation / Run completes with correct sources and no unsupported capability promises |
| History | Previous Invocations, events, artifacts, and application acceptance records remain readable |
| Host and scheduling, if used | Run code repair or recurring research against test targets and inspect linked records |

Record versions, backup batch, inputs, execution IDs, and differences. Validate credentials through the integrations that use them; the knowledge-only case has no external credential and cannot establish that Vault decryption works.
