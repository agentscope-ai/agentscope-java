# DataAgent cluster deployment

This guide shows a minimal 3-replica deployment with shared workspace and
Redis coordination. The same shape works on Kubernetes (StatefulSet behind a
Service, PVC for the workspace, a Redis Deployment) — the docker-compose
sample below is the smallest reproducible example.

## What needs to be shared across replicas

| Subsystem | Shared via | Reason |
|---|---|---|
| Per-user workspace (`memory/`, `sessions/`, `tasks/`, `skills/`, …) | Redis-backed `RemoteFilesystem` (`dataagent.session.redis.enabled=true`) | A user routed to R2 must see files R1 wrote against the same `(userId, agentId)` namespace. |
| Sandbox state / session snapshots | Redis (same flag) | A user routed to R2 must be able to resume the sandbox that R1 started. |
| Tool-event SSE | Redis Pub/Sub (auto when Redis enabled) | An SSE subscriber on R2 must see tool calls fired by the agent running on R1. |
| User channel bindings | Redis hash (auto when Redis enabled) | Preferences set on R1 should take effect immediately on R2. |
| JPA tables (`dataagent_user`, `dataagent_agent`, `dataagent_contribution`) | Shared RDBMS (MySQL / PostgreSQL via `jdbc` profile) | The H2 default is single-node only; cluster deployments must point every replica at the same external database. |
| `${dataagent.workspace}/.agentscope/shared/` | Shared filesystem (NFS / EFS) | The OverlayFilesystem's lower layer; every replica must read the same set of approved contributions. Approval writes from any replica must be visible to all. |
| In-memory log SSE | (not shared — single-replica view) | Admin "live logs" only show events from the replica serving that connection. Documented behaviour. |

## docker-compose sample

```yaml
# docker-compose.yml
services:
  redis:
    image: redis:7-alpine
    command: ["redis-server", "--appendonly", "yes"]
    volumes: ["redis-data:/data"]

  mysql:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?set me}
      MYSQL_DATABASE: dataagent
      MYSQL_USER: dataagent
      MYSQL_PASSWORD: ${DATAAGENT_DB_PASSWORD:?set me}
    volumes: ["mysql-data:/var/lib/mysql"]

  # NFS-backed shared workspace; in a real deployment this is an EFS / Filestore
  # / Azure Files mount instead of a local bind.
  workspace-init:
    image: alpine
    command: ["sh", "-c", "mkdir -p /workspace/.agentscope/shared && chown -R 1000:1000 /workspace"]
    volumes: ["dataagent-workspace:/workspace"]

  dataagent-1: &dataagent
    image: agentscope/dataagent:latest
    depends_on: [redis, mysql, workspace-init]
    environment:
      DATAAGENT_JWT_SECRET: ${DATAAGENT_JWT_SECRET:?set me}
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY:?set me}
      DATAAGENT_WORKSPACE: /workspace
      SPRING_PROFILES_ACTIVE: prod,jdbc
      DATAAGENT_DB_URL: jdbc:mysql://mysql:3306/dataagent?useSSL=false&serverTimezone=UTC
      DATAAGENT_DB_USER: dataagent
      DATAAGENT_DB_PASSWORD: ${DATAAGENT_DB_PASSWORD:?set me}
      DATAAGENT_JPA_DDL_AUTO: validate
      DATAAGENT_SESSION_REDIS_ENABLED: "true"
      DATAAGENT_SESSION_REDIS_HOST: redis
      DATAAGENT_SESSION_REDIS_PORT: "6379"
    volumes: ["dataagent-workspace:/workspace"]

  dataagent-2: { <<: *dataagent }
  dataagent-3: { <<: *dataagent }

  lb:
    image: nginx:alpine
    depends_on: [dataagent-1, dataagent-2, dataagent-3]
    ports: ["8080:80"]
    volumes: ["./nginx.conf:/etc/nginx/nginx.conf:ro"]

volumes:
  redis-data:
  mysql-data:
  dataagent-workspace:
    driver_opts:
      type: nfs
      o: "addr=nfs.internal,rw,nfsvers=4"
      device: ":/exports/agentscope"
```

Minimal `nginx.conf` (round-robin; session affinity NOT required because the
session state lives in Redis):

```nginx
events {}
http {
  upstream dataagent_replicas {
    server dataagent-1:8080;
    server dataagent-2:8080;
    server dataagent-3:8080;
  }
  server { listen 80; location / { proxy_pass http://dataagent_replicas; } }
}
```

## Validating the cluster

1. **Bindings** — create a binding on R1, fetch on R2 within the same second:

   ```bash
   curl -X POST -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
     -d '{"channelId":"chatui","language":"zh-CN"}' \
     http://dataagent-1:8080/api/user/bindings
   curl -H "Authorization: Bearer $JWT" http://dataagent-2:8080/api/user/bindings
   # → returns the binding created on dataagent-1
   ```

2. **Session continuity** — send turn #1 to R1, turn #2 to R2:

   ```bash
   curl -X POST -H "$AUTH" -H 'Content-Type: application/json' \
     -d '{"message":"Hi, my name is Alice"}' \
     http://dataagent-1:8080/api/chat/send
   curl -X POST -H "$AUTH" -H 'Content-Type: application/json' \
     -d '{"message":"What name did I just give you?"}' \
     http://dataagent-2:8080/api/chat/send
   # → R2 reply references "Alice"
   ```

3. **Marketplace contribution propagation** — submit a contribution on R1,
   approve it as admin against R2, confirm a chat on R3 picks up the new
   skill on the next session reset:

   ```bash
   curl -X POST -H "$USER_AUTH" -H 'Content-Type: application/json' \
     -d '{"targetType":"skill","targetPath":"cohort-builder/SKILL.md","payload":"# Cohort builder\n..."}' \
     http://dataagent-1:8080/api/me/contributions
   # → { "id": 42, ... "status": "PENDING" }

   curl -X POST -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
     -d '{"note":"looks good"}' \
     http://dataagent-2:8080/api/admin/contributions/42/approve
   # → 200; file written under /workspace/.agentscope/shared/skills/cohort-builder/SKILL.md
   # → visible to dataagent-3 because every replica mounts the same NFS volume
   ```

4. **Cross-replica SSE** — open the SSE stream on R2, send a chat from R1, see
   the `TOOL_CALL` events propagate.

## Pre-flight checks that fail loudly

Startup will refuse to come up if any of these are misconfigured:

- `dataagent.jwt.secret` left at the dev placeholder in a non-`dev` profile.
- `dataagent.workspace` blank in a non-`dev` profile.
- `dataagent.session.redis.enabled=true` with `dataagent.workspace` pointing
  at an ephemeral path (`/tmp/`, `/var/tmp/`, `/private/tmp/`, `/dev/shm/`).
- `spring.jpa.hibernate.ddl-auto=update` against MySQL/PostgreSQL in a cluster:
  pin the schema with Flyway / Liquibase and set `DATAAGENT_JPA_DDL_AUTO=validate`.

These are intentional: silent misconfiguration in cluster mode corrupts user
state in ways that are hard to recover from after the fact.

## What still lives on a single replica

- Admin "live log" SSE (`/api/admin/runtime/logs`) — only sees events from the
  replica the admin happens to connect to. Documented; not a bug.
- The deployment banner printed at startup describes which subsystems are
  cluster-aware on this exact replica — read it once after enabling Redis to
  confirm everything you expect is on.
