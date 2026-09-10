---
title: "Endpoints: integrate Agent capabilities into applications"
---

[简体中文](/v2/zh/service/endpoints)

An Endpoint exposes an Agent, Team or published Workflow revision through a stable interface with authentication, schemas and invocation records. Callers do not need internal runtime or scheduler addresses.

## Choose a mode

| Mode | Use | Submission path |
| --- | --- | --- |
| conversation | Multi-turn interaction with a conversation-capable target | `/invoke/v1/endpoints/{slug}/conversations` |
| job | Trackable work with an Agent, Team or Workflow | `/invoke/v1/endpoints/{slug}/jobs` |

Create an Endpoint from the target's publication area. Set its slug, mode, schemas, authentication, timeout and payload limit. Review Readiness before publishing and resolve missing targets or capabilities. Do not use job-only Teams or Workflows as conversation targets.

## Publish and authenticate

A Draft is not a live invocation entry. Publish creates a usable release. With `api_key` authentication, create a credential and send `X-API-Key`. With `platform` authentication, send `Authorization: Bearer`. These credential types are not interchangeable.

Store keys in your application backend's secret configuration. Give callers separate credentials with appropriate expiry, rotation and revocation. Browser code should call your backend rather than embed a long-lived key.

## Submit a job

Copy the generated example from Endpoint details. This example assumes slug `report` and an input schema permitting `request`; use your published schema:

Set `BASE_URL` to the Gateway's public origin, such as `https://agentscope.example.com` without a trailing slash, and `ENDPOINT_TOKEN` to the credential for this Endpoint.

```bash
curl --fail-with-body "$BASE_URL/invoke/v1/endpoints/report/jobs" \
  -H "X-API-Key: $ENDPOINT_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: report-order-001' \
  --data '{"title":"Prepare report","description":"Summarize the supplied material","input":{"request":"List the open questions"}}'
```

Save `invocationId`, `status`, `statusUrl` and `eventsUrl`. Follow the returned URLs instead of inventing internal Run paths:

```bash
curl --fail-with-body "$BASE_URL$STATUS_PATH" -H "X-API-Key: $ENDPOINT_TOKEN"
curl -N --fail-with-body "$BASE_URL$EVENTS_PATH" -H "X-API-Key: $ENDPOINT_TOKEN" -H 'Accept: text/event-stream'
```

Set `STATUS_PATH` and `EVENTS_PATH` to returned relative URLs. Use absolute returned URLs directly without prepending BASE_URL. Read `invocation.result` according to the Endpoint's output schema.

## Continue a conversation

Submit `{"message":"Describe your responsibilities"}` initially and save `conversationId`. Send later turns to `/invoke/v1/conversations/{conversationId}/turns` with a message and a new Idempotency-Key. Subscribe using that turn's returned eventsUrl, retaining its invocationId query parameter.

## Retries and states

Retransmit the same logical request with the same Idempotency-Key and content. Use a new key for genuinely new work. accepted, dispatching, running and waiting are nonterminal. completed is successful; failed, cancelled and timed_out require corresponding handling.

A disconnected SSE stream is not proof of failure; query statusUrl first. Check authentication and permissions for 401/403, follow rate-limit guidance for 429, and fix schema errors before retrying. Unconditionally changing keys can repeat business side effects.

## Change releases

Publish an Endpoint release to switch its target and retain history. Rollback points the entry at a previous release; it does not restore databases or undo external operations. Disable prevents new use. Inspect the specific invocation/execution when cancelling existing work.

Related: [API reference](/v2/en/service/api-reference) · [Workflows](/v2/en/service/workflows).
