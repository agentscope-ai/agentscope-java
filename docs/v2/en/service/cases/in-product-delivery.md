---
title: "In-product delivery: generate a CRM proposal"
description: "Submit a Job from an opportunity, restore progress, download files, and review delivery."
zh_link: /v2/zh/service/cases/in-product-delivery
---

A salesperson selects “generate proposal” on an opportunity. The application sends requirements and product sources; an Agent prepares a proposal and open questions in the background. Users can leave and return to the same task, then review before sending. Marketing briefs, research reports, and presentations follow this pattern.

Start with one specialist Agent. The CRM owns login, opportunity permissions, versions, and sending. Service owns durable execution, interactions, and artifacts. Add a Team only when specialist delegation is useful.

## 1. Prepare fixed input

Download the [request fixture](/examples/service/in-product-delivery/input.json.txt) as `input.json`. It is a complete Job request for fictional opportunity OPP-104. Three short sources are inline; no separate upload or Memory configuration is required.

| Source | Fixed fact | Required treatment |
| --- | --- | --- |
| customer / customer-v1 | 80 stores, web support and read-only order lookup; SSO, region, peak load unknown | Separate requirements from open questions |
| catalog / product-v1 | Web knowledge answers; order lookup needs integration; no voice or offline app | Do not invent capabilities or accuracy guarantees |
| delivery / delivery-v1 | Suggested four-week PoC starts after access and sample readiness | Do not promise production launch |

## 2. Prepare and publish execution

Configure an Agent that reads inputs, writes files, and publishes Artifacts. Verify a real file can be downloaded through Service. Instructions require `proposal.md` and `open-questions.md`, source IDs and versions, and explicit unknowns. Actual tools must create and publish the files.

[Publish](/v2/en/service/endpoints) a job-mode Endpoint named `proposal`. Its input schema must accept request, opportunity_id, revision, and sources. The result can contain a summary and source list; Artifacts carry the files. Before configuring output schema or resultMapping, inspect the execution target's real output structure.

Prepare Bash, curl, jq, `BASE_URL`, and a backend-held `ENDPOINT_KEY` with invoke/read scopes. Add interaction or cancellation scopes only as needed.

## 3. Submit from the opportunity

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/proposal/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: opportunity-104-revision-1' \
  --data-binary @input.json)
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID" \
  -H "X-API-Key: $ENDPOINT_KEY"
```

Store “OPP-104 / revision 1 → Invocation ID” in the application database. HTTP 202 means accepted; the query can still show active work. Network retries reuse the key and body. A new requirements revision uses a new key and retains previous results.

## 4. Restore the view and handle feedback

```bash
SNAPSHOT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID/snapshot" \
  -H "X-API-Key: $ENDPOINT_KEY")
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
curl --fail-with-body -N -G \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID/events/stream" \
  -H "X-API-Key: $ENDPOINT_KEY" --data-urlencode "after=$CURSOR"
```

Render the snapshot before applying events. Reopening an opportunity observes the existing Invocation. Read required actions when input is needed and let authorized people respond. Check capabilities before sending additional input; see [Unified service API](/v2/en/service/service-api).

This task can list unknown SSO and region requirements rather than wait indefinitely. Revising a completed proposal creates a new Job, with the CRM retaining the revision relationship.

## 5. Review and write back

Read `invocation.status/result` and `GET /invoke/v1/invocations/{id}/artifacts`; download actual files using returned addresses. After confirming the overall terminal state, check:

- Both files contain real content and cite customer-v1, product-v1, and delivery-v1.
- Voice and offline apps are not claimed as available; order integration dependencies are explicit.
- SSO, region, and peak load remain open; the PoC estimate has prerequisites.
- The opportunity revision is still current; old output cannot overwrite new requirements.

Only then move the CRM to “reviewed” or “ready to send.” Sending customer email is a separate CRM action.

## 6. Failures and regression

| Condition | Application response |
| --- | --- |
| Source access fails or files are missing | Show failure or missing delivery |
| partial_succeeded | Inspect failed steps and missing files before accepting partial output |
| Refresh or disconnect | Restore snapshot/cursor; replace the snapshot if the cursor expired |
| Unsupported request | State limits and questions rather than make commitments |
| Catalog changes to product-v2 | Submit new work and check source versions; preserve old output |

Production can replace inline sources with authorized tools or Memory. Record versions actually read. Fixtures and criteria describe expected behavior; validate real models, file tools, and CRM writeback in your environment.
