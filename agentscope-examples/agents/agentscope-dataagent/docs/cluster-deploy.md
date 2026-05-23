# DataAgent cluster deployment

This guide shows a minimal 3-replica deployment with shared workspace and Redis
coordination. The same shape works on Kubernetes (StatefulSet behind a Service,
PVC for the workspace, a Redis Deployment) — the docker-compose sample below is
the smallest reproducible example.

## What needs to be shared across replicas

| Subsystem | Shared via | Reason |
|---|---|---|
| Sandbox state / session snapshots | Redis (`claw.session.redis.enabled=true`) | A user routed to R2 must be able to resume the sandbox that R1 started. |
| Tool-event SSE | Redis Pub/Sub (auto when Redis enabled) | An SSE subscriber on R2 must see tool calls fired by the agent running on R1. |
| User channel bindings | Redis hash (auto when Redis enabled) | Preferences set on R1 should take effect immediately on R2. |
| `users.json`, `usage/*`, `sessions/*`, `workspace/` | Shared filesystem (NFS / EFS) | The workspace files are still authoritative; every replica reads/writes the same paths. |
| In-memory log SSE | (not shared — single-replica view) | Admin "live logs" only show events from the replica serving that connection. Documented behaviour. |

## docker-compose sample

```yaml
# docker-compose.yml
services:
  redis:
    image: redis:7-alpine
    command: ["redis-server", "--appendonly", "yes"]
    volumes: ["redis-data:/data"]

  # NFS-backed shared workspace; in a real deployment this is an EFS / Filestore
  # / Azure Files mount instead of a local bind.
  workspace-init:
    image: alpine
    command: ["sh", "-c", "mkdir -p /workspace/.agentscope && chown -R 1000:1000 /workspace"]
    volumes: ["claw-workspace:/workspace"]

  claw-1: &claw
    image: agentscope/claw:latest
    depends_on: [redis, workspace-init]
    environment:
      CLAW_JWT_SECRET: ${CLAW_JWT_SECRET:?set me}
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY:?set me}
      CLAW_WORKSPACE: /workspace
      SPRING_PROFILES_ACTIVE: prod
      CLAW_SESSION_REDIS_ENABLED: "true"
      CLAW_SESSION_REDIS_HOST: redis
      CLAW_SESSION_REDIS_PORT: "6379"
      CLAW_SANDBOX_ENABLED: "true"
      CLAW_SANDBOX_ISOLATION: USER
    volumes: ["claw-workspace:/workspace"]
    # If sandbox=true, the host Docker socket must be mounted so the agent can
    # spawn its sandbox containers.
    volumes_extra: &dockersock ["/var/run/docker.sock:/var/run/docker.sock"]

  claw-2: { <<: *claw }
  claw-3: { <<: *claw }

  lb:
    image: nginx:alpine
    depends_on: [claw-1, claw-2, claw-3]
    ports: ["8080:80"]
    volumes: ["./nginx.conf:/etc/nginx/nginx.conf:ro"]

volumes:
  redis-data:
  claw-workspace:
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
  upstream claw_replicas { server claw-1:8080; server claw-2:8080; server claw-3:8080; }
  server { listen 80; location / { proxy_pass http://claw_replicas; } }
}
```

## Validating the cluster

1. **Bindings** — create a binding on R1, fetch on R2 within the same second:

   ```bash
   curl -X POST -H 'Authorization: Bearer $JWT' -H 'Content-Type: application/json' \
     -d '{"channelId":"chatui","language":"zh-CN"}' http://claw-1:8080/api/user/bindings
   curl -H 'Authorization: Bearer $JWT' http://claw-2:8080/api/user/bindings
   # → returns the binding created on claw-1
   ```

2. **Session continuity** — send turn #1 to R1, turn #2 to R2:

   ```bash
   curl -X POST -H "$AUTH" -H 'Content-Type: application/json' \
     -d '{"message":"Hi, my name is Alice"}' http://claw-1:8080/api/chat/send
   curl -X POST -H "$AUTH" -H 'Content-Type: application/json' \
     -d '{"message":"What name did I just give you?"}' http://claw-2:8080/api/chat/send
   # → R2 reply references "Alice"
   ```

3. **Cross-replica SSE** — open the SSE stream on R2, send a chat from R1, see
   the `TOOL_CALL` events propagate.

## Pre-flight checks that fail loudly

Startup will refuse to come up if any of these are misconfigured:

- `claw.jwt.secret` left at the dev placeholder in a non-`dev` profile.
- `claw.workspace` blank in a non-`dev` profile.
- `claw.session.redis.enabled=true` with `claw.workspace` pointing at an
  ephemeral path (`/tmp/`, `/var/tmp/`, `/private/tmp/`, `/dev/shm/`).
- `claw.sandbox.isolation=SESSION` without `claw.sandbox.enabled=true`.

These are intentional: silent misconfiguration in cluster mode corrupts user
state in ways that are hard to recover from after the fact.

## What still lives on a single replica

- Admin "live log" SSE (`/api/admin/runtime/logs`) — only sees events from the
  replica the admin happens to connect to. Documented; not a bug.
- The deployment banner printed at startup describes which subsystems are
  cluster-aware on this exact replica — read it once after enabling Redis to
  confirm everything you expect is on.
