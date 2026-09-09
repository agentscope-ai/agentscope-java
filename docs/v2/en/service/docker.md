# Docker deployment

Release deployments use the image references in `agentscope-service/deploy/compose.yaml`. The existing `agentscope-service/docker-compose.yml` builds a development stack from source and uses different initialization settings.

## Services and storage

| Service | Container port | Exposure |
| --- | --- | --- |
| Gateway | 8080 | `127.0.0.1:18080` by default |
| Control | 8081 | Private network |
| Dataplane | 8082 | Private network |
| Scheduler | 8083 | Private network |
| PostgreSQL | 5432 | Private network |

Compose waits for database and upstream health before starting dependent services. `docker compose up -d --wait --wait-timeout 600` waits for the stack. A healthy Gateway endpoint alone does not verify the business workflow.

Three named volumes hold PostgreSQL, shared workspaces and artifacts. Do not use development database-reset scripts on release installations.

## Remote access

Point your HTTPS reverse proxy at the Gateway, allow long-lived SSE connections and disable event-stream buffering. Set `BUILDER_OAUTH_PUBLIC_URL` in `.env` to the public origin used by users, then recreate the relevant container. Configure external callbacks against that public address.

Use `BIND_ADDRESS` and `GATEWAY_PORT` to change the listener. Database and internal component ports do not need public exposure.

## Configuration changes

After editing `.env`, run:

```bash
docker compose up -d --wait --wait-timeout 600
docker compose ps
```

`init-env.sh` preserves existing files and secrets. JWT, internal-token and Vault settings must match across the components using them. Vault-key rotation is not an ordinary configuration refresh.

Before changing versions, follow [Backup and recovery](operations.md). See [Releasing](releasing.md) for artifact publication.
