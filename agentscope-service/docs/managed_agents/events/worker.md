# Environment Worker work API

Aligned with Claude Managed Agents self-hosted sandboxes: **outbound-only** workers execute
externalized hands tools and resume turns via `user.tool_result`.

## Auth

| Client | Auth |
|---|---|
| Worker (poll / ack / heartbeat / stop / pending-tools / tool-results / skills) | Header `X-Builder-Environment-Key` (plaintext shown once on env create / `POST …/keys/rotate`) |
| Console (list / get / stats) | User JWT + RUN/EDIT |

## Work queue routes

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/environments/{id}/work/poll` | Long-poll claim (`workerId`, `timeoutMs`); includes `metadata` from session `resources[]` |
| `GET` | `/api/environments/{id}/work` | List (`state` filter) |
| `GET` | `/api/environments/{id}/work/{workId}` | Get one |
| `GET` | `/api/environments/{id}/work/stats` | Counts + oldest queued age |
| `POST` | `/api/environments/{id}/work/{workId}/ack` | → `active` (no Brain-side WorkspaceSandbox) |
| `POST` | `/api/environments/{id}/work/{workId}/heartbeat` | Refresh lease (≤15s recommended) |
| `POST` | `/api/environments/{id}/work/{workId}/stop` | → `stopped` |
| `POST` | `/api/environments/{id}/work/{workId}` | Optional workDir update |
| `POST` | `/api/environments/{id}/keys/rotate` | New env key (JWT, EDIT) |

## Session data-plane routes (env key)

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/environments/{id}/sessions/{sessionId}/pending-tools` | Pending `agent.tool_use` with `id` / `name` / `input` |
| `POST` | `/api/environments/{id}/sessions/{sessionId}/tool-results` | Body `{ "results": [ { tool_use_id, name, content, is_error } ] }` → resume turn |
| `GET` | `/api/environments/{id}/sessions/{sessionId}/skills` | Skills bundle for staging under `/workspace/skills` |

## State machine

```text
queued → starting → active → stopping → stopped
              ↑_______________|  (stale heartbeat → reclaim → queued)
```

## Execution model

1. Brain registers schema-only hands tools on `self_hosted` (no local FS/shell).
2. Model emits tool_use → `TOOL_SUSPENDED` → persist `agent.tool_use`, status `requires_action`.
3. Worker polls work, acks, downloads skills, stages metadata files, executes locally.
4. Worker posts tool results → Brain resumes with `ToolResultBlock`s.
5. Heartbeat while active; stop when session drains.

Removed: `…/worker/register`, `…/work/claim`, `…/ready`, `…/complete`.

**Dev vs prod:** the in-process worker (`InProcessEnvironmentWorker`) was removed in the four-plane split.
Dev and prod share one shape: `HandsWorkerMain` from the service-scheduler jar, outbound-only.
