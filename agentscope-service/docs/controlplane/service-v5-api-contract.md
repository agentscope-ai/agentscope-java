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

## Work Sources

Work Source adapters implement `HandleEvent`, `FetchWork`, `ApplyIssueCommand`, `PublishComment`, and `Reconcile`. GitHub webhook deliveries are deduplicated by delivery ID. Local comments use `pending_sync` plus an outbox until GitHub returns an external comment ID.

## Invocation Gateway

- `POST /invoke/v1/endpoints/{slug}/conversations`
- `POST /invoke/v1/endpoints/{slug}/jobs`
- `GET /invoke/v1/jobs/{issueId}`
- `GET /invoke/v1/jobs/{issueId}/events`

Job creation requires an idempotency key and returns `202` with `issueId`, `runId`, and `statusUrl`. The Console Playground uses these same routes.

## Schema policy

Before the v5 release, `cp` and `rt` migrations are squashed into their baseline files. Upgrade in development is schema rebuild only. `dp` is rebuilt only when its wire/storage contract changes.
