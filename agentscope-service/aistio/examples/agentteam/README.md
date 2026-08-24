# AgentTeams E2E sample

End-to-end checklist for Claude-parity Agent Teams against aistiod's store-backed
`/api/v1/teams` API. One lead + two workers (one Managed, one BYO-shaped).

## Prerequisites

- aistiod with runtime store (memory or postgres) and product plane enabled for Managed members
- Console JWT or API bearer for `/api/v1/*`
- Optional: BUILDER_DATA_URL so Managed wake events reach the data plane
- Healthy dataplane registration for the BYO `agentRef` (or accept activation failure → recovery)

```bash
export AISTIO_URL="${AISTIO_URL:-http://localhost:8081}"
export TOKEN="Bearer <console-jwt-or-api-token>"
export NS=default
export OWNER_ID="<product-user-id>"
export MANAGED_AGENT_ID="<product-agent-id>"   # lead / worker-managed
export BYO_AGENT_REF="${BYO_AGENT_REF:-byo-writer}"
```

## Scenario script

```bash
chmod +x ./run.sh
./run.sh
```

`run.sh` walks:

1. Create team (lead managed + worker-managed + worker-byo) — defaults include Auto recovery + TTL
2. Lead assign path: create task → assign → claim/start → complete
3. Self-claim path: create unassigned task → claim
4. Dynamic spawn member
5. List messages / events (mailbox; empty `to` broadcasts)
6. Complete team via `POST .../complete` (TTL retention); use `DELETE` only for force teardown

Data-plane callers may authenticate with `X-Builder-Internal-Token` (same as dataplane register).
Standalone `scripts/dev-up.sh` starts the shared TeamRuntime (message dispatcher + sweeper) without Kubernetes.

## Manual matrix


| Scenario | How to verify |
|---|---|
| Lead assign | Task `owner` set while `pending`, then claim by assignee |
| Self-claim | Blank `owner`, `POST .../claim` by worker |
| Dynamic spawn | `POST .../members` with `name=dyn-1` |
| Crash recovery | Kill worker DP → member `Lost` → Auto recovery with `RecoveryContext` |
| planApproval | Member with `planApproval=true` (UI/API field); approval tools on lead |
| complete → terminated | `DELETE /api/v1/teams/{name}` terminates store sessions |
| Shared artifacts | Write under `teams/{teamId}/...` via BaseStore / workspace; mailbox only short text + refs |

## Console

Open Teams zone in the console (`/teams`):

1. **New team** with Managed lead + workers
2. Open detail → task board columns
3. Managed member **Open chat** → `/sessions/{managedSessionId}`
4. BYO member shows **Observe only**

## Related docs

- [contract.md](../../docs/zh/controlplane/contract.md) — `team-coordination` / `team_join`
- [design.md §4](../../docs/zh/blogs/design.md) — Agent Teams architecture
- [managed-agents-followups.md](../../docs/zh/controlplane/managed-agents-followups.md) — Managed human chat path
