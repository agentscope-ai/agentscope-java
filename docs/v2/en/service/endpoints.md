---
title: "Publish and invoke Endpoints through APIs"
description: Publish an Agent, Team, or Workflow contract, issue application credentials, and submit and observe work.
zh_link: /v2/zh/service/endpoints
---

An Endpoint exposes an Agent, Team, or published Workflow revision as a stable business API. Callers need its address, contract, and credentials, without knowing where member Agents run or how work is scheduled.

This page uses HTTP to create, publish, credential, and invoke an Endpoint. Continue with the [Unified service API](/v2/en/service/service-api) for events, interactions, input, cancellation, and recovery. Visual publishing is covered in [Console orchestration](/v2/en/service/console/orchestration).

## Choose an invocation mode

| Mode | Target and purpose | Submission route |
| --- | --- | --- |
| `job` | Agent, Team, or Workflow completing a unit of work | `/invoke/v1/endpoints/{slug}/jobs` |
| `conversation` | A session-capable single Agent, with multiple turns | `/invoke/v1/endpoints/{slug}/conversations` |

Managed, External, and Hosted Agents can be targets when they support the required execution capability. Teams and Workflows currently use Job. Check readiness before publishing and capabilities afterward; catalog presence does not guarantee every interaction command.

## Create a service entry point

Prepare a runnable Agent first. Examples use Bash, `curl`, and `jq`, with a platform identity authorized for the target scope and publishing operations. The helper below sends management requests; application credentials are created separately.

```bash
export BASE_URL='https://YOUR_SERVICE_HOST'
export TOKEN='YOUR_PLATFORM_USER_TOKEN'
export TENANT='YOUR_TENANT'
export NAMESPACE='YOUR_NAMESPACE'
export AGENT_ID='YOUR_AGENT_ID'

platform_api() {
  local api_path="$1"
  shift
  curl --fail-with-body -sS "$BASE_URL$api_path" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" "$@"
}
```

Create a Job whose input accepts a `request` field:

```bash
ENDPOINT_JSON=$(platform_api /api/v1/endpoints \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg agent "$AGENT_ID" '{
      tenant:$tenant, namespace:$namespace, name:"Notes service", slug:"notes-service",
      targetType:"agent", targetRef:$agent, invocationMode:"job",
      authPolicy:{type:"api_key"},
      inputSchema:{type:"object", required:["request"],
        properties:{request:{type:"string"}}}
    }')")
ENDPOINT_ID=$(printf '%s' "$ENDPOINT_JSON" | jq -er '.endpoint.id')
ENDPOINT_VERSION=$(printf '%s' "$ENDPOINT_JSON" | jq -er '.endpoint.version')
platform_api "/api/v1/endpoints/$ENDPOINT_ID/readiness"
```

The response contains a draft `endpoint`, which does not yet accept production calls. `slug` determines its public URL; name or slug conflicts return `409`. For a Team, use `targetType:"team"` and its ID. For a Workflow, use `targetType:"orchestration_revision"` and the published revision ID, not the draft definition ID.

Optional configuration includes `outputSchema`, `resultMapping`, `timeoutSeconds`, `maxPayloadBytes`, and `rateLimit`. See [API parameter reference](/v2/en/service/api-reference). Start with a small contract before adding constraints.

## Validate and publish

Check readiness for target/mode compatibility and verify execution resources. Publish using the version you just read:

```bash
platform_api "/api/v1/endpoints/$ENDPOINT_ID/publish" \
  --data "$(jq -n --argjson version "$ENDPOINT_VERSION" '{version:$version}')"
```

Publishing creates a release that fixes the public contract and target configuration. Check for `endpoint.status=published`. On a version conflict, reread and review the change rather than blindly replaying an old publication decision with a new version.

## Issue credentials to the calling application

This example uses `api_key` authentication. Create an Application representing the business caller, then issue its credential for this Endpoint:

```bash
APPLICATION_JSON=$(platform_api /api/v1/applications \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    '{tenant:$tenant, namespace:$namespace, name:"Notes application"}')")
APPLICATION_ID=$(printf '%s' "$APPLICATION_JSON" | jq -er '.application.id')

CREDENTIAL_JSON=$(platform_api "/api/v1/endpoints/$ENDPOINT_ID/credentials" \
  --data "$(jq -n --arg app "$APPLICATION_ID" \
    '{applicationId:$app, name:"backend", scopes:["invoke","read","cancel","interact"]}')")
export ENDPOINT_KEY=$(printf '%s' "$CREDENTIAL_JSON" | jq -er '.secret')
```

Store the returned `secret` in the backend's credential store and send it as `X-API-Key`. Creating or publishing an Endpoint **does not generate a key automatically**. Explicit `applicationId` and nonempty `scopes` are required. This example grants invocation, reading, cancellation, and interaction; Webhook management additionally needs `webhooks:write`.

An Application shares invocation ownership across its keys, allowing a replacement key to read existing work. Rotation does not revoke the previous key immediately: migrate callers, then revoke the old credential. Browser clients should call your backend instead of embedding a long-lived key.

For authorized platform users, create the Endpoint with `authPolicy:{type:"platform"}` and use a user Bearer token without issuing an application key. The [Quickstart](/v2/en/service/first-session) uses this path. Do not interchange the two authentication policies.

## Submit and follow the invocation

```bash
curl --fail-with-body -sS "$BASE_URL/invoke/v1/endpoints/notes-service/capabilities" \
  -H "X-API-Key: $ENDPOINT_KEY"

RECEIPT=$(curl --fail-with-body -sS "$BASE_URL/invoke/v1/endpoints/notes-service/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: notes-job-001' \
  --data '{"title":"Prepare notes","input":{"request":"Lee owns the Friday installation notes. Review is Monday, time unconfirmed. Extract the action items."}}')
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
INVOCATION_URL="$BASE_URL/invoke/v1/invocations/$INVOCATION_ID"

SNAPSHOT=$(curl --fail-with-body -sS "$INVOCATION_URL/snapshot" -H "X-API-Key: $ENDPOINT_KEY")
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
curl --fail-with-body -N -G "$INVOCATION_URL/events/stream" \
  -H "X-API-Key: $ENDPOINT_KEY" --data-urlencode "after=$CURSOR"
```

Submission returns `202`, `invocationId`, and status, snapshot, and event URLs. Save the response. Render the snapshot first, then consume events after `as_of` to include messages and tool results generated before subscription. The example uses standard Invocation paths; you can also use the returned URLs directly.

Stopping SSE observation does not cancel background execution. Use another request to `GET /invoke/v1/invocations/{id}` for `invocation.status` and the final `invocation.result`. Only the overall Invocation state establishes the result; a member or tool completing does not finish the Team. Inspect steps when the state is `partial_succeeded`.

## Multi-turn conversations

For an Agent published in `conversation` mode, start with `{"message":"Summarize the meeting notes"}`. Save `conversationId`, then send subsequent messages and new idempotency keys to `POST /invoke/v1/conversations/{conversationId}/turns`. Each turn has its own Invocation.

A Conversation accepts only one active Invocation at a time. Runtime support for active input, approval, and recovery differs; use capabilities to control the UI. For direct Managed file, subagent, and checkpoint operations, use the [Native session API](/v2/en/service/session-event-log).

## Retries and state

Reuse the original Idempotency-Key and body after a network error. Use a new key for new work. accepted, dispatching, running, and waiting are not completion. Cancellation first enters cancel_requested; wait for a confirmed outcome.

After disconnection, reload the snapshot or resume after the last applied cursor without resubmitting work. See the [Unified service API](/v2/en/service/service-api) for events and interaction, and [SSE](/v2/en/service/sse-events) for the distinction between invocation and native Managed event resources.

## Update releases and disable entry points

| Operation | API and parameters |
| --- | --- |
| Edit draft configuration or operational limits | `PATCH /api/v1/endpoints/{id}`, with `version` and changed fields |
| List releases | `GET /api/v1/endpoints/{id}/releases` |
| Deploy a target version | `POST /api/v1/endpoints/{id}/releases`, with `version`, `targetRef`, optional `reason` |
| Select a previous release | `POST /api/v1/endpoints/{id}/releases/{releaseId}/rollback`, with `version` |
| Disable new calls | `POST /api/v1/endpoints/{id}/disable`, with `version` |
| Rotate / revoke a credential | `POST /api/v1/endpoints/{id}/credentials/{credentialId}/rotate`; `DELETE /api/v1/endpoints/{id}/credentials/{credentialId}` |

Published schemas cannot be changed in place through an ordinary PATCH. A new release does not rewrite existing Invocation contracts or undo external effects. Disabling an Endpoint does not cancel existing work; cancel its Invocation separately when needed.
