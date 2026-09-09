# Backup, upgrade and recovery

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

A backup is qualified only when database, files and keys recover together. This release preparation does not claim multi-replica HA or zero-downtime upgrades.
