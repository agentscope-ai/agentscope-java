# Aistio Product CP — Phase 0 Contract (lightweight)

Fast-dev draft. Enough to code against; not a frozen OpenAPI.

## Main console / gateway paths (core)

| Method | Path | Owner | Notes |
|--------|------|-------|-------|
| POST | `/api/auth/login` | CP | `{username,password}` → `{token,userId,username,roles}` |
| GET | `/api/auth/me` | CP | Bearer JWT |
| GET | `/api/user/profile` | CP | |
| POST | `/api/user/change-password` | CP | |
| GET/POST | `/api/agents` | CP | list / create |
| GET/PUT/DELETE | `/api/agents/{id}` | CP | |
| POST | `/api/agents/{id}/archive` | CP | |
| GET | `/api/agents/{id}/versions` | CP | |
| GET | `/api/agents/{id}/versions/{v}` | CP | |
| GET/POST | `/api/environments` | CP | |
| GET/DELETE | `/api/environments/{id}` | CP | |
| POST | `/api/environments/{id}/archive` | CP | |
| GET/POST | `/api/sessions` | CP | lifecycle only |
| GET | `/api/sessions/{id}` | CP | |
| POST | `/api/sessions/{id}/archive` | CP | |
| DELETE | `/api/sessions/{id}` | CP | |
| POST/GET | `/api/sessions/{id}/events*` | **DP** | turns / SSE |
| CRUD | `/api/memory-stores/**` | CP | |
| CRUD | `/api/vaults/**` | CP | |
| CRUD | `/api/deployments/**` | CP | |

Skipped this wave: marketplace, draft AI, templates, scaffold, activity, clone/share extras.

## Table ownership (single Postgres instance)

Database: `builder`. Schemas: `cp` (product) + `dp` (runtime).

### `cp.*`

- `users`, `agents`, `agent_versions`, `environments`, `sessions`
- `memory_stores`, `memories`, `memory_versions`
- `vaults`, `vault_credentials`
- `deployments`, `resource_shares`, `agent_shares`

### `dp.*`

- `session_events`, `agent_states`
- `coord_leases`, `coord_hitl`, `coord_work`, `coord_workers`

Invariant: DP code never SELECTs `cp.*` after Phase 3; use internal APIs. CP never touches `dp.*`.

## Internal APIs (DP / Scheduler → CP)

Auth: `X-Builder-Internal-Token` (+ optional `X-Builder-Internal-User`).

### `GET /api/internal/sessions/{id}/resolve`

One-shot payload for `HarnessAgentBuildService`:

```json
{
  "session": {
    "id": "sess_…",
    "ownerId": "u_…",
    "agentId": "ag_…",
    "agentOwnerId": "u_…",
    "agentVersion": 1,
    "agentRefType": "latest",
    "agentOverridesJson": null,
    "environmentId": "env_…",
    "memoryStoreIds": [],
    "vaultIds": [],
    "resources": null,
    "status": "active"
  },
  "agentSnapshot": { },
  "workspacePath": "/data/workspaces/…",
  "environment": {
    "id": "env_…",
    "name": "default-local",
    "type": "local",
    "config": {}
  },
  "vaultCredentials": [],
  "memoryMounts": []
}
```

### Others

- `POST /api/internal/sessions/find-or-create`
- `PATCH /api/internal/sessions/{id}/runtime` — `{status?, stopReason?}`
- `GET /api/internal/environments/{id}`
- `GET /api/internal/agents/{ownerId}/{agentId}/versions/{v}`
- `POST /api/internal/vaults/resolve` — body `{vaultIds, ownerId}`
- `GET /api/internal/memory-stores/{id}/mount`
- `POST /api/internal/deployments/{id}/fire`
- `GET /api/internal/channels/config`

## Deploy notes

- One Postgres; Go CP + Java DP share the instance (`cp` / `rt` / `dp` schemas).
- Single binary: `aistiod`. One port serves `/api/*`, `/api/v1/*`, and the console SPA.
  Kubernetes is optional (`AISTIO_ENABLE_KUBERNETES=false` for a standalone control plane).
- Gateway `BUILDER_CONTROL_URL` → `aistiod` after cutover.
