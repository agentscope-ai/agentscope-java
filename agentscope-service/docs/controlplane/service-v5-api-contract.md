# AgentScope Service v5 API contract

This document is the executable terminology companion to ADR-0002. Unless a route explicitly belongs to the invocation Gateway, control-plane resources use `/api/v1`.

## Agent Catalog

- `GET|POST /api/v1/agents`
- `GET|PATCH /api/v1/agents/{agentId}`
- `GET|POST /api/v1/agents/{agentId}/bindings`
- `PATCH /api/v1/agents/{agentId}/bindings/{bindingId}`
- `GET /api/v1/agents/{agentId}/instances`
- `POST /api/v1/agent-registrations`
- `POST /api/v1/agent-registrations/{agentId}/credentials/rotate`
- `DELETE /api/v1/agent-registrations/{agentId}/credentials/{credentialId}`
- `GET /api/v1/agents/{agentId}/definition`
- `GET /api/v1/agents/{agentId}/versions`

External first registration requires a trusted bootstrap token or workload identity and returns the plaintext registration credential exactly once. Follow-up registration uses `X-Agent-Registration-Credential` or the configured workload identity. Runtime reports may update observed capabilities, health, capacity, and last-seen time only.

## Stable references

The following fields are UUIDs named `agentId`: Issue Agent assignee references, Team leader and members, AgentTask, orchestration Agent nodes, runtime policies, Sessions created by an endpoint, and ExecutionAttempt snapshots. `displayName` and `agentKey` are never accepted as assignment identities.

Runtime candidates reference a `bindingId`. The binding configuration is discriminated by kind:

- Managed: `ownerRef`, `managedDefinitionRef`
- External: `instanceSelector`
- Hosted: `runtimeProfileId`, `runtimePoolId`

## Teams

- `GET|POST /api/v1/teams`
- `GET|PATCH /api/v1/teams/{teamId}`
- `GET /api/v1/teams/{teamId}/overview`
- `POST /api/v1/teams/{teamId}/members`
- `PATCH|DELETE /api/v1/teams/{teamId}/members/{memberId}`

A Team is a persistent leader-first roster and policy template. It has no Runtime identity of its own. The leader and every worker reference a stable `agentId`; the leader cannot also appear as a worker member. A Team can be `active` or `disabled`. Disabled Teams reject new Issue assignments, Workflow materialization, and Endpoint Jobs, while already-running work continues from its immutable snapshot.

Starting Team work creates exactly one Team coordinator RunNode and one initial leader AgentTask. It does not fan out every member automatically. The leader may delegate through structured Agent mentions, create child work, or extend the adaptive Run graph. Targets outside the frozen roster fail closed unless `allowExternalDelegation` is enabled. The leader explicitly completes or fails the coordinator after worker tasks, child work, and added Run nodes converge.

The first Team participation in a Run freezes the complete roster, roles, instructions, policy, and member Runtime Binding overrides in `RunTeamSnapshot`. All task routing, runtime selection, content/budget enforcement, timeouts, and result return paths use that snapshot. Editing or disabling the persistent Team affects future Runs only.

Team member changes increment the Team version. Member roles are unique inside a Team, one Agent may occupy only one worker role, and member responsibility or Runtime policy can be updated without deleting and recreating the member.

## Work Sources

Work Source adapters implement `HandleEvent`, `FetchWork`, `ApplyIssueCommand`, `PublishComment`, and `Reconcile`. GitHub webhook deliveries are deduplicated by delivery ID. Local comments use `pending_sync` plus an outbox until GitHub returns an external comment ID.

## Invocation Gateway

- `GET|POST /api/v1/endpoints`
- `GET|PATCH|DELETE /api/v1/endpoints/{endpointId}`
- `GET /api/v1/endpoints/{endpointId}/readiness`
- `POST /api/v1/endpoints/{endpointId}/publish|disable`
- `GET|POST /api/v1/endpoints/{endpointId}/releases`
- `POST /api/v1/endpoints/{endpointId}/releases/{releaseId}/rollback`
- `GET|POST /api/v1/endpoints/{endpointId}/credentials`
- `POST /api/v1/endpoints/{endpointId}/credentials/{credentialId}/rotate`
- `POST /api/v1/endpoints/{endpointId}/credentials/{credentialId}/reveal`
- `DELETE /api/v1/endpoints/{endpointId}/credentials/{credentialId}`
- `GET /api/v1/endpoints/{endpointId}/invocations`
- `POST /invoke/v1/endpoints/{slug}/conversations`
- `POST /invoke/v1/conversations/{conversationId}/turns`
- `GET /invoke/v1/conversations/{conversationId}`
- `GET /invoke/v1/conversations/{conversationId}/events`
- `POST /invoke/v1/endpoints/{slug}/jobs`
- `GET /invoke/v1/jobs/{invocationId}`
- `GET /invoke/v1/jobs/{invocationId}/events`
- `GET /invoke/v1/jobs/{invocationId}/artifacts`
- `GET /invoke/v1/jobs/{invocationId}/artifacts/{artifactId}`
- `POST /invoke/v1/jobs/{invocationId}/cancel`

An Endpoint is a stable, governed API façade, not the Agent's native API and not a runtime instance address. Agent publication is optional; Team and Workflow publication is the canonical external API. Conversation mode targets one Agent whose selected Binding supports interactive sessions: Managed provides that capability through the product Session service, External instances advertise `conversation-inbound`, and Hosted Runtime materializes each turn as a hidden operational AgentTask/ExecutionAttempt while retaining one durable control-plane Session. Hosted providers resume with their opaque provider session ID when available; otherwise the persisted transcript is replayed. Job mode targets an Agent, Team, or immutable orchestration revision and creates the native Issue → Run → AgentTask → Attempt chain. The Issue created for an Endpoint Job is an `endpoint_job` operational record with automatic completion: it stays out of the default Work Hub list and moves to `done` when the root Run succeeds. Human-created Work remains review-gated. Team and Workflow targets remain Job-only.

Endpoints start as `draft` and must pass a side-effect-free readiness check before `publish`. Initial publication creates release 1. Deploying a newer Workflow revision or rolling back appends an immutable release while preserving the Endpoint slug, contract, and credentials. `disable` rejects new calls while existing Invocation results remain readable; `archive` removes all public access. API-key credentials retain a verification hash and an AES-GCM encrypted value so authorized Agent developers and administrators can explicitly reveal and copy them again; normal list responses remain masked and reveal responses are marked non-cacheable. Rotation supports an explicit overlap window until the previous key is revoked.

`AISTIO_ENDPOINT_CREDENTIAL_KEY` is the deployment-managed encryption master key and must be identical on every control-plane replica. It falls back to `BUILDER_JWT_SECRET` for local compatibility. Changing either value without re-encrypting stored credentials makes existing values unrecoverable, although hash-based invocation authentication continues to work.

Agent, Team, and Workflow detail pages are the publication and discovery context: the target is selected by the owner page, not re-entered by the user, and every API belonging to that object is listed there. The standalone Published APIs catalog is removed. Each listed API links to its complete detail surface for lifecycle, metadata, contract, credentials, release history, traffic, rollback, and public-path testing, then returns to its owning object.

Every call requires an idempotency key. It is scoped by Endpoint, mode, and authenticated principal. The public identity is `invocationId`; internal `issueId`, `runId`, or `sessionId` are links rather than public identifiers. Job creation returns `202` with `invocationId`, `issueId`, `runId`, `statusUrl`, and `eventsUrl`. Conversation turns return `invocationId`, `conversationId`, `turnId`, `sessionId`, and event/status URLs. Event streams are resumable SSE streams using `Last-Event-ID`. Endpoint `Test API` uses these same Gateway routes.

## Scope and Runtime Host enrollment

- `GET /api/v1/me/scope` returns the server-authoritative tenant, namespace, scope mode, and whether
  the console may expose a selector.
- `POST /api/v1/runtime-host-enrollment-tokens` creates a short-lived `asre_` token bound to that
  scope. In multi mode tenant and namespace are required and remain subject to operator authorization.
- `POST /api/v1/runtime-host-enrollments/exchange` accepts only the `asre_` bearer plus a local
  `hostKey`, returning an `asrh_` Runtime Host credential bound to all three values.
- `POST /api/v1/runtime-host-enrollments` remains the authenticated interactive-login exchange.

In single scope mode, URL/body scope supplied by a caller cannot override the configured tenant or
namespace. Tenant and namespace remain persistence and routing fields even when absent from the UI.

## Chat and API testing

- `GET|POST /api/v1/chats`
- `GET|PATCH /api/v1/chats/{chatId}`
- `POST /api/v1/chats/{chatId}/turns`

Chat is the user-facing direct conversation surface. A Chat is private to its creator, targets one conversation-capable Agent, and owns title, pin, and archive state independently of its runtime Session. Each turn uses the same runtime policy, Binding validation, instance generation, capacity checks, durable Session event log, and hosted resume behavior as other conversation callers. Chat does not become collaborative Work automatically; users create an Issue explicitly when ownership, status, priority, review, or team visibility is required.

Agent Detail links to Chat. Team and Workflow actions remain Job-oriented. Endpoint Detail retains a context-specific `Test API` surface because Endpoint tests use the public path and therefore exercise the real credential, publication state, schema, rate limit, and release.

## Schema policy

Before the v5 release, `cp` and `rt` migrations are squashed into their baseline files. Upgrade in development is schema rebuild only. `dp` is rebuilt only when its wire/storage contract changes.
