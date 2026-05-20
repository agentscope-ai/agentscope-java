# agentscope-claw-web

A **read-only admin console** for monitoring a DataAgent (`agentscope-claw`) deployment.

This is a standalone Spring Boot application that provides an admin UI for observing the runtime state of `agentscope-claw`. It **cannot modify user data** and **cannot issue chat messages** — those capabilities live in `agentscope-claw`. The admin sees per-tenant activity, sessions, workspace mutations, and aggregate usage; the underlying agent is a single business agent (default: DataAgent), not a marketplace.

---

## What it does

- Proxies live runtime data from `agentscope-claw`'s `/api/admin/runtime/*` endpoints
- Reads static workspace files (`.agentscope/agentscope.json`, `.agentscope/users.json`) from the shared directory
- Provides a React admin SPA accessible to users with the `admin` role

## What it does NOT do

- Does not start any agent runtime (no `ClawBootstrap`)
- Does not accept user logins (admin users log in via `agentscope-claw`'s `/api/auth/login` and use the same JWT here)
- Does not write to any workspace files
- Does not handle chat or session operations

---

## Setup

### Prerequisites

1. `agentscope-claw` must be running (default: `http://localhost:8080`)
2. Both services must share:
   - The same **JWT signing secret** (`claw.jwt.secret` / `claw-web.jwt-secret`)
   - The same **workspace directory** (`claw.workspace` / `claw-web.workspace`) — or claw-web is pointed at the same shared filesystem path

### Configuration (`application.yml`)

| Property | Default | Description |
|---|---|---|
| `claw-web.claw-url` | `http://localhost:8080` | Base URL of the running agentscope-claw instance |
| `claw-web.jwt-secret` | _(same as claw's secret)_ | JWT verification secret. Must match `claw.jwt.secret`. |
| `claw-web.workspace` | `$CWD` | Path to the shared workspace directory |
| `claw-web.system-token` | _(empty)_ | System JWT used **only** for unattended health probes (e.g. `/api/admin/status`). User-initiated proxy calls forward the logged-in admin's own bearer to claw instead, so the admin's identity shows up in claw's access logs. Legacy property `claw-web.admin-token` (env `CLAW_ADMIN_TOKEN`) is still read as a fallback. |
| `server.port` | `8090` | HTTP port (different from claw's 8080) |

### Running

```bash
java \
  -Dclaw-web.claw-url=http://localhost:8080 \
  -Dclaw-web.workspace=/var/agentscope \
  -DCLAW_JWT_SECRET=<same-secret-as-claw> \
  -jar target/agentscope-claw-web-*.jar
```

Open **http://localhost:8090** → login with an admin account (credentials are checked by `agentscope-claw`).

---

## Admin login flow

```
Browser → POST http://claw:8080/api/auth/login → JWT (signed with shared secret)
Browser → GET  http://claw-web:8090/overview  → Authorization: Bearer <jwt>
                    ↓ JwtService verifies using same secret
                    ↓ ROLE_ADMIN required → access granted
```

---

## API endpoints (admin only)

All endpoints require `ROLE_ADMIN`. Every endpoint is `GET` — the admin console is read-only.

### Platform observability (proxied to claw runtime)

| Path | Description |
|---|---|
| `/api/admin/overview` | Platform overview |
| `/api/admin/instances` | Registered agent instances |
| `/api/admin/agents` | Globally registered agents (from `agentscope.json`) |
| `/api/admin/sessions?limit=N` | Active sessions |
| `/api/admin/sessions/{key}` | Session detail |
| `/api/admin/sessions/{key}/tree` | Recursive sub-agent fan-out tree |
| `/api/admin/sessions/{key}/workspace/events?since=&limit=` | Workspace mutation timeline for a session |
| `/api/admin/channels` | Channel state |

### Usage statistics (proxied)

| Path | Description |
|---|---|
| `/api/admin/usage/summary` | Aggregate usage summary |
| `/api/admin/usage/hourly?hours=24` | Hourly buckets |
| `/api/admin/usage/daily?days=30` | Daily buckets |
| `/api/admin/usage/top-users?days=7&n=10` | Top users by usage |
| `/api/admin/usage/top-agents?days=7&n=10` | Top agents by usage |
| `/api/admin/usage/users-rollup?days=30` | Per-user rollup |
| `/api/admin/usage/agents-rollup?days=30` | Per-agent rollup |
| `/api/admin/usage/user/{userId}/daily?days=30` | Single-user daily series |

### Workspace inspection (filesystem-backed)

| Path | Description |
|---|---|
| `/api/admin/agents/{id}/workspace` | Workspace summary tree |
| `/api/admin/agents/{id}/workspace/agents-md` | Raw `AGENTS.md` |
| `/api/admin/agents/{id}/workspace/memory` | Memory directory listing |
| `/api/admin/agents/{id}/workspace/skills` | Skill list |
| `/api/admin/agents/{id}/workspace/skills/{name}` | Single skill view |
| `/api/admin/channels/{id}/bindings` | Channel routing bindings (from `agentscope.json`) |
| `/api/admin/config/agentscope` | Raw `agentscope.json` |

### Console-local

| Path | Description |
|---|---|
| `/api/admin/users` | User list (from `users.json`) |
| `/api/admin/status` | Claw connectivity status |
| `/api/admin/debug/info` | Debug info + log stream URL |

> Auth forwarding: user-initiated proxy calls forward the caller's `Authorization` header to claw verbatim, so claw attributes the request to the actual admin user. Only `/api/admin/status` (and other system probes) fall back to the configured `system-token`.

---

## Building

```bash
mvn package -pl agentscope-claw-web -am -DskipTests
```
