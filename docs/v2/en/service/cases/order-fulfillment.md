---
title: "Enterprise operations: resolve order fulfillment exceptions"
description: "Coordinate independently deployed AgentScope applications across orders, inventory, logistics, and after-sales systems."
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A customer asks why an order has not shipped and requests delivery tomorrow. Specialized Agents investigate orders, inventory, logistics, and service policy; a fulfillment Leader coordinates a resolution and verifies actual business outcomes. This reduces cross-system lookup and handoffs while retaining evidence, decisions, and execution history.

All five Agents, including the Leader, are **AgentScope Java applications registered as External Agents**. Applications retain their deployment and tools. Service provides discovery, dispatch, collaboration, and observation; business systems retain their rules and data.

## Prepare data and connectivity

Download the [fixed business dataset](/examples/service/order-fulfillment/business-data.json.txt) as `business-data.json`. It contains three fictional orders, inventory, logistics, and policies for tool development and acceptance. It is not a running OMS/WMS/TMS service or a complete Agent application.

Prepare Java 17+, matching AgentScope and `agentscope-extensions-aistio` versions, a model, Service connectivity, and application credentials for the target Namespace. For standard Service deployments, start with [Java HTTP registration](/v2/en/service/external-agent).

First back tools with the fixture and validate individual tasks, then connect actual enterprise APIs. Enforce caller access to orders in the business system; a supplied `customerId` is not an identity credential.

## 1. Develop five business Agents

| Application / Agent key | Tool responsibility | Output |
| --- | --- | --- |
| Coordinator / `fulfillment-lead` | Organize investigation, compare options, delegate by phase | Consolidated result, evidence, action status |
| Orders / `order-agent` | Query state/version and perform permitted changes | State, promised date, restrictions |
| Inventory / `inventory-agent` | Query warehouses and transfer conditions | Available quantities, warehouses, validity constraints |
| Logistics / `logistics-agent` | Read shipment events and candidate delivery estimates | Observed facts, estimates, guarantees if any |
| After-sales / `after-sales-agent` | Read policy, create and query resolution records | Policy evidence, record ID, action result |

Implement business functions in each AgentScope Toolkit. These are **example application tool contracts**, not built-in Service APIs:

| Tool | Input | Required output or check |
| --- | --- | --- |
| `get_order` | `orderId` | State, `version`, source, query time |
| `get_inventory` | SKU and quantity | Availability and constraints; querying does not reserve stock |
| `get_logistics` | `orderId` | Shipment or absence of shipment, estimate evidence |
| `get_policy` | Order and proposed action | Allowed actions, approval requirement, policy version |
| `create_resolution` | Order, action, expected version, approval record, idempotency key | Server-side authorization/version checks and a real record ID |
| `get_resolution` | Resolution ID | Actual outcome, distinguishing accepted from completed |

Diagnosis uses read-only tools. Business writes validate the approved action, order version, and authorization in the service. Model text saying “approved” does not authorize a change; an invented number is not a real resolution record.

## 2. Connect task execution

Use a distinct `agentKey` per application and stable, distinct `instanceKey` values per replica. Adapt the following initialization fragment:

```java
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.adapter.HarnessAgentTaskStarter;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;

AgentScopeAdapter adapter = new AgentScopeAdapter();
adapter.setAgentTaskStarter(new HarnessAgentTaskStarter(
    taskAgentFactory,
    new CollaborationClient(new ControlPlaneHttpClient(controlUrl, serviceToken))));
SessionBridge bridge = Aistio.instrument(catalogAgent, config, adapter);
// Close the bridge and release application-owned Agents/execution resources on shutdown.
```

This is an integration fragment. `catalogAgent` is the directory Agent; `taskAgentFactory` is a `Supplier<HarnessAgent>` providing the role's model, tools, instructions, and isolated execution context. Configure `config` using the [registration guide](/v2/en/service/register-agentscope-agent). An administrator supplies `controlUrl` and `serviceToken` to the trusted application backend, not Endpoint callers.

`HarnessAgentTaskStarter` connects task context, collaboration actions, and result reporting. Implement business tools separately and avoid shared mutable conversation state. The Leader must also use delegation and coordinator completion correctly. Validate success, failure, cancellation, and concurrent isolation before forming a Team. Registration alone does not establish these capabilities; see [External execution](/v2/en/service/external-agent-execution).

## 3. Create the fulfillment Team

Under **DESIGN → Agents**, check all five External Bindings, online instances, and task capabilities. Under **DESIGN → Teams**, select `fulfillment-lead` as Leader and add the four specialists.

```text
Identify the order and customer goal, then delegate relevant investigations.
Cite business sources, versions, or query times; distinguish estimates from confirmed facts.
In diagnose phase, propose only: do not change orders or create resolution records.
In execute phase, use the exact approved plan and recheck version, stock, and delivery conditions.
After any action, query the real outcome. Report partial failure without duplicate writes.
Consolidate cause, evidence, options, approvals, outcomes, and next steps; complete coordination.
```

Create an Issue for `O-1001`, requesting arrival on `2026-09-15`, with phase `diagnose`. Specify that these are fixed exercise dates rather than relative to today.

## 4. Verify investigation

The result must establish:

- `O-1001` is `awaiting_stock`; original warehouse `W-A` has zero availability.
- `W-B` has eight units, which does not mean they were reserved or the warehouse changed.
- There is no tracking number. Candidate earliest arrival is `2026-09-16`, without a guarantee.
- The requested `2026-09-15` cannot be promised from the evidence; a warehouse change needs approval.
- The diagnosis proposes options and open questions without modifying business state.

`O-1002` has shipped and calls for shipment investigation. `O-1003` is cancelled and cannot reuse the warehouse-change plan. Also test unknown orders, inconsistent data, and unavailable business APIs, including explicit escalation or uncertainty.

## 5. Publish for an online application

Publish a Team Job Endpoint named `order-triage` from **Connections**. Configure its input schema as follows. This diagnostic interface accepts only `diagnose`; callers cannot select an execution phase themselves.

```json
{
  "type": "object",
  "properties": {
    "orderId": {"type": "string"},
    "requestedArrival": {"type": "string"},
    "phase": {"type": "string", "enum": ["diagnose"]}
  },
  "required": ["orderId", "requestedArrival", "phase"],
  "additionalProperties": false
}
```

Set `BASE_URL` to the Gateway origin and `ENDPOINT_TOKEN` to this Endpoint's key. Submit from the business backend:

```bash
curl --fail-with-body "$BASE_URL/invoke/v1/endpoints/order-triage/jobs"   -H "X-API-Key: $ENDPOINT_TOKEN"   -H 'Content-Type: application/json'   -H 'Idempotency-Key: order-O-1001-triage-001'   --data '{"title":"Investigate O-1001","description":"Investigate the delay using the fixture. Propose a resolution without executing changes.","input":{"orderId":"O-1001","requestedArrival":"2026-09-15","phase":"diagnose"}}'
```

Retain `invocationId`, `eventsUrl`, and `statusUrl`. Display progress using the [SSE guide](/v2/en/service/sse-events) and read `invocation.result` after completion. Its business structure follows your output contract; intermediate model messages are not final customer responses. Keep the Endpoint key in the business backend, which supplies and verifies customer authorization context.

## 6. Approve, execute, and verify

For the complete loop, connect **diagnosis Team → approval → execution Team** in a Workflow. Team nodes may use the same team with different phases and tool authorization:

1. Diagnosis produces the exact plan, order version, action, and evidence. Define structured output before mapping actual fields in the designer.
2. Present that plan to the designated approver. Rejection ends this disposition without business changes.
3. Execution reads the approval and refreshes business state. Changed versions or material conditions require a new diagnosis rather than an outdated action.
4. Create a resolution with a stable business idempotency key, query its outcome, and consolidate results. An accepted record with failed execution is partial completion.

Validate and publish a revision following [Workflow guidance](/v2/en/service/workflows), then publish a separate Endpoint for the complete process. Automatic Endpoint Job completion is not business approval. SSE reconnection must not repeat business writes.

## Acceptance and production integration

| Check | Evidence |
| --- | --- |
| Correct diagnosis | Traceable order, stock, logistics, and policy sources |
| Approved action | Action, object, version, and approval match |
| Idempotency | Retransmission does not create duplicate resolution records |
| Real state | Business queries confirm records and order state |
| Recoverable failure | Query existing actions after timeout; preserve errors and partial results |
| Isolation | No shared conversational state or unauthorized cross-customer reads |

Keep tool contracts and criteria when replacing fixtures with production sources, authentication, and business validation. Disable exercise Endpoints, resolve outstanding runs, and clean simulated records afterward. Fixtures and SDK fragments do not establish a completed enterprise integration.
