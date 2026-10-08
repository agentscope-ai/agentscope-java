# Migration to the Session API

Applications now call Agents, Teams, and Workflows through `/api/v1/agent-sessions`. A Session selects and freezes its execution target. Each submission creates a Turn. Managed Agent, External Agent, and Hosted Agent are runtime bindings of an Agent target, rather than separate application invocation protocols.

## Update clients and credentials together

The previous `/api/v1/endpoints/**` management routes and `/invoke/v1/**` execution routes are no longer registered. There is no publish, release, slug, or Endpoint credential step. Create credentials under `/api/v1/applications/{applicationId}/credentials` with explicit target grants and scopes. Store the returned `apiKey` in the application backend. Old Endpoint keys are not Session API keys.

Create a Session with `target: {type, id}`. Agent definitions optionally accept `version`; Workflow definitions optionally accept a published `revisionId`. Workflow publication remains necessary to freeze a process definition. Team and Workflow Turns represent independent tasks; Managed Sessions retain conversation context across Turns.

Submit to `/api/v1/agent-sessions/{sessionId}/turns` with `message` or `input` and a stable `Idempotency-Key`. Save both the Session and Turn IDs. Restore the corresponding snapshot and reconnect from its `as_of`. Session and Turn event cursors are distinct. Interactions, cancellation, artifacts, and notifications all stay under the selected Session or Turn.

The original Managed native `agent` field remains accepted as a Session-create alias. New clients should use `target`. Native runtime Session and Turn IDs are private: use the public IDs returned by this API. Existing runtime Session IDs are not automatically imported as application Sessions. Create new application Sessions for new work; historical native execution records remain available through operational diagnostics.

Managed tool answers now use `{request_id, payload: {allow, reason}}` on the public Turn `/actions` resource. An application key cannot impersonate a designated human approver. Poll the returned command receipt to distinguish acceptance from execution. Cancel and resume also require an idempotency key. Read capabilities before offering controls.

The Python `ServiceClient` now starts with `create_session(target)` and `submit(session_id, ...)`. The TypeScript client is `frontend/src/api/serviceSessions.ts`. The console's Agent, Team, and Workflow panels provide direct Session examples and Application credential management. The old publication pages have been removed.

## Upgrade storage and routing

Apply the control-plane migration `0114_session_api` using the standard migration runner. It removes the old Endpoint and Endpoint credential foreign keys from execution records. Session identity and frozen contracts are persisted in the existing durable KV store, using the `service-sessions` namespace; creating a Session does not create a hidden Endpoint row.

Physical execution tables retain their previous names to preserve stored work and avoid a destructive table rewrite. The execution engine also retains internal compatibility types. These names do not represent a second public API. Earlier accepted execution records are retained for worker recovery and operational diagnostics; their removed HTTP routes do not become Session routes automatically. Back up the database before upgrading. The down migration refuses to recreate incompatible foreign keys while new Session executions exist; it does not delete them to make rollback succeed.

Deploy the matching Gateway and control plane together. The Gateway sends every `/api/v1/agent-sessions/**` resource to the control plane for Session ownership and application credential checks. The control plane then calls the Managed dataplane through its authenticated internal connection. Do not retain the previous public route that forwards these paths directly to the dataplane.

Public file uploads use Session ownership, return `id`, and accept raw bytes with `X-File-Name`. Managed structured input references that ID as `file_id`. Other targets expose only their supported file and interaction capabilities. Public Session and Turn Webhooks share the `X-AgentScope-Signature` HMAC protocol. The compatibility configuration name `AISTIO_ENDPOINT_CREDENTIAL_KEY` now protects Webhook signing secrets; application API keys are stored only as hashes.

## Validation scope

The refactor includes HTTP tests for Agent/Team/Workflow Sessions, queued cancellation, application isolation, file ownership, idempotency, snapshot replay, webhook signatures, restart recovery, and retention. The Managed integration test uses real PostgreSQL account, catalog, definition, and Session stores with a deterministic HTTP dataplane fixture; it checks file materialization, frozen versions, public IDs, and completion ordering without making model calls.

These checks supplement the execution engine's regression tests. They do not constitute a production deployment, a real-provider model run, or a multi-replica capacity qualification. The historical regression campaign in `regression-checklist.md` predates this public API change and must not be presented as evidence that the new routes were deployed.
