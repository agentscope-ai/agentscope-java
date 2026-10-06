---
title: "Specialist Agent service: callable by another Agent"
description: "Wrap a specialist task as an asynchronous tool with an Invocation handle, evidence, and authorization."
zh_link: /v2/zh/service/cases/agent-as-tool
---

A procurement assistant interprets the user's goal and calls a supplier-evidence review service. It receives facts, missing evidence, and questions, then continues its own work. The independently published specialist can serve applications and other Agents.

The application implements the parent tool wrapper. Service provides the Endpoint and Invocation; neither a Team nor automatic public MCP export is assumed.

## 1. Fix the input and scope

Download the [request fixture](/examples/service/agent-as-tool/input.json.txt) as `input.json`. Fictional supplier V-101 has these sources:

| Source | Content | Permitted conclusion |
| --- | --- | --- |
| profile / v1 | Self-reported 12 staff and web support | Attribute the claims as self-reported |
| review / v1 | No independent security review or delivery SLA supplied | Identify missing evidence, not a passed review |

The business result should contain confirmed_facts, missing_evidence, and questions. These are application-defined fields. The specialist cannot approve purchasing or treat a parent's authorization claim as a credential.

## 2. Publish the capability

Configure a single Agent for read-only evidence review. [Publish](/v2/en/service/endpoints) job Endpoint `supplier-review`, accepting request, supplier_id, and sources. Inspect actual outputs before configuring resultMapping/outputSchema. Separate self-reports, independent evidence, and unknowns.

Grant the parent application appropriate access, a timeout, and a budget. With Bash, curl, jq, BASE_URL, and ENDPOINT_KEY, first verify an independent call:

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/supplier-review/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: parent-task-301-supplier-101-call-1' \
  --data-binary @input.json)
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID" \
  -H "X-API-Key: $ENDPOINT_KEY"
```

## 3. Wrap HTTP calls as asynchronous tools

Avoid holding a synchronous tool call open for a long task. These are proposed **application tool contracts**:

| Tool | Adapter responsibility |
| --- | --- |
| start_supplier_review | Validate user/supplier permissions, submit a Job, return its Invocation as task_id |
| get_supplier_review | Verify ownership and query state, results, and evidence |
| answer_supplier_review | Answer the actual action type using the authorized approver |
| cancel_supplier_review | Check capabilities, request cancellation, track the final state |

Persist parent task/tool call → Invocation. Derive the idempotency key from the original tool call. A response such as `{"task_id":"actual Invocation ID","state":"accepted"}` describes the adapter, not a new Service route.

For accepted/running/waiting, arrange later queries or callback wakeup. Read results on completed; expose missing branches on partial_succeeded. Pass failed/cancelled/timed_out explicitly to the parent instead of silently reasoning from an empty result.

## 4. Propagate identity and control

Keep keys in the backend and authorize every operation against the authenticated user and original task. Do not expose keys, arbitrary download links, or other users' handles to the model. Show evidence and effects when approval is needed and use the actual approver's identity. Model-generated “approved” text is not approval.

Limit nested depth, count, and cumulative cost. When a parent is cancelled, the adapter follows its stored mapping to cancel downstream work and confirms the outcome. Public calls do not automatically establish parent-child Invocation cancellation or shared budgets for arbitrary application calls.

For MCP, expose these tools through an application MCP server. Internal collaboration MCP does not automatically export Endpoints. Plain HTTP tools are a valid starting point.

## 5. Acceptance and failures

| Test | Required result |
| --- | --- |
| Parent tool call replay | Same Invocation, no duplicate logical work |
| Fixed supplier evidence | Staff count attributed as self-reported; review and SLA remain missing |
| Human input required | Parent keeps the handle and waits for a valid response |
| Failure or partial delivery | Retain errors and useful evidence; do not invent a conclusion |
| Another user supplies task_id | Deny reads and operations |
| Parent cancelled/timed out | Explicitly propagate cancellation; wait for confirmation |
| Recursive calls | Enforce application depth, count, and budget limits |

The fixture defines input and expected judgments. Implement and run the parent adapter, MCP server, and procurement integration separately. Purchasing approval remains in the business process.
