# ADR-0002: AgentScope Service v5 identity and execution boundaries

- Status: Accepted
- Date: 2026-08-28
- Supersedes: the Agent identity and registration portions of pre-v5 contracts

## Context

The service already has the durable Issue-first collaboration kernel and the physical execution model required by v5. Its upper layers, however, used runtime names and separate Managed/External/Hosted concepts as if they were logical Agent identities. That makes scale-out look like new Agents, permits ambiguous name claims, and leaks runtime topology into Teams and Workflows.

v5 is an unreleased, destructive contract reset. Development environments rebuild the `cp` and `rt` schemas; there is no old Agent API, dual write, old schema, or name-reference compatibility layer.

## Decision

`rt` is authoritative for the unified Agent Catalog, collaboration state, orchestration state, sessions, and execution facts. `cp` owns Managed definitions, versions, users, and hosted product resources only. The same `agent_id` is stored in both databases without a cross-database foreign key.

The identity and execution chain is:

```text
Agent -> AgentBinding -> AgentInstance | RuntimeHost -> ExecutionAttempt
```

- `Agent` is the stable logical identity. Assignment, Team membership, orchestration Agent nodes, comments, and policies reference its UUID.
- `AgentBinding` selects one of `managed`, `external-application`, or `hosted-runtime`. Those values are runtime attachments, not Agent types.
- `AgentInstance` is an observed process for a Managed or External binding. Scale-out creates instances, never logical Agents.
- `RuntimeHost` advertises provider capacity for Hosted bindings. Host registration never creates a logical Agent.
- `ExecutionAttempt` freezes `agentId`, `bindingId`, selected `instanceId` or `hostId`, generation, capabilities, constraints, and provider configuration. Later catalog changes cannot mutate the attempt.

External applications claim `(tenant, namespace, agentKey)` through a trusted bootstrap/workload identity on first registration. The transaction creates one active Agent, one External binding, one default runtime policy, one instance, and one registration credential. Only the credential hash is persisted. Later instances require that credential or a matching workload identity. A bootstrap token alone cannot reclaim an existing key.

Managed creation is a recoverable workflow: create a provisioning Catalog Agent in `rt`, create the Managed definition in `cp`, then create its binding and policy and activate the Agent. A failure records `provisioning_failed`; retry converges on the same identity. There is no cross-database pseudo-transaction.

Hosted Agents are created explicitly and bind to a RuntimeProfile and RuntimePool. Runtime Host registration remains independent.

Tenant and namespace remain the storage, authorization, scheduling, and runtime routing boundary. A
single-machine or single-enterprise deployment runs in `single` scope mode: the console hides both
fields and the server canonicalizes requests to its configured scope. Multi-scope deployments may
expose a selector. `namespace` deliberately keeps its name because Workspace is a separate product
resource. Runtime Host enrollment tokens carry the authoritative tenant/namespace; `agentscope
connect` persists that returned scope and does not ask normal users to enter it.

## Work and execution state ownership

Issue-first applies to durable work collaboration. A normal online conversation can create or resume a Session without creating an Issue.

- Issue owns business state, assignment, review, and acceptance.
- OrchestrationRun and RunNode own workflow state.
- AgentTask owns an Agent's durable work obligation.
- ExecutionAttempt owns one physical execution and its fencing/lease state.
- Session owns context continuity.

A successful Run moves native work to review by default; it does not mark the Issue done. Acceptance requires a human or an explicit external rule.

## Work sources and endpoints

GitHub is an external authoritative Work Source for title, body, state, and external comments. AgentScope stores a projection plus its own Run, Task, Attempt, Approval, Artifact, Inbox, and audit facts. Outbound mutations update the projection only after the adapter succeeds.

Endpoint reuses the existing Gateway and is a governed API façade rather than a replacement for an Agent runtime's native API or UI. Publishing an individual Agent is optional. Conversation mode targets one Agent with an inbound-capable runtime and manages a Session without an Issue. Job mode targets an Agent, Team, or immutable orchestration revision and creates an Issue and Run with mandatory, principal-scoped idempotency. Public callers track a stable Invocation instead of using Issue, Run, or Session identities as the API contract.

## Authorization and product areas

The console exposes one role-aware navigation rather than separate Work Hub and Agent Center menu spaces. Work, Design, and Resources are the primary navigation groups. Sessions, Executions, and Activity remain separate domain projections and stable deep-link routes, but are entered from the relevant Agent, Team, Workflow, Issue, Endpoint, or Chat detail instead of a top-level Observe menu. Channels are external ingress resources under Design; they bind stable logical targets, never `AgentInstance` processes. Cross-Agent activity and runtime-infrastructure APIs retain an internal `operations` authorization capability for `operator` and `admin`; URL namespaces do not define product areas. The fixed roles are `user`, `agent_developer`, `operator`, and `admin`. Direct API calls and console routes enforce the same matrix.

## Consequences

- All old name-based Agent references and Managed-only `/api/agents` routes are removed.
- Missing, disabled, cross-scope, or mismatched bindings fail closed.
- Runtime health is aggregated from instances and never overwrites Agent lifecycle status.
- v5 smoke tests begin from rebuilt `cp` and `rt` schemas.
