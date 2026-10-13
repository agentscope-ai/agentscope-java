# Service API without Console

The default example runs two deterministic async workers and makes no model calls. It creates an Application, starts workers whose External registration atomically creates their logical Agents and runtime bindings, configures their runtime policies, creates a Team and declared Workflow, makes all three targets callable through Sessions, and issues one application credential with explicit target grants. Do not pre-create these External Agents without bindings: such Agents remain provisioning and cannot accept External registration. The standalone Agent emits three messages and three tool calls. The Team leader delegates once, releases its initial execution slot, accepts each child on its own follow-up, and finalizes only after every child is accepted. A real Managed member and a designated human approval are optional.

Run against the matching Service version with PostgreSQL migrations applied, task scheduling enabled, and `/api/v1/agent-runtime/exchange` routed to the control plane. Keep `--enable-asdp=true` on the current control plane because HTTP shares its handlers (disabling it disables both transports). The default HTTP transport does not require exposing the ASDP gRPC port or an inbound worker port.

```bash
# From this directory, preferably inside a virtual environment.
python -m pip install ../../sdk/python
export AGENTSCOPE_BASE_URL='http://localhost:8080'
export AGENTSCOPE_PLATFORM_TOKEN='YOUR_PLATFORM_TOKEN'
export AGENTSCOPE_BOOTSTRAP_TOKEN='YOUR_AGENT_REGISTRATION_BOOTSTRAP_TOKEN'
export AGENTSCOPE_TENANT='default'
export AGENTSCOPE_NAMESPACE='default'
python bootstrap.py --output service-demo.json
```

Use the tenant/namespace that your platform user can manage. Keep bootstrap running: it owns the two worker processes. Credentials are written to `service-demo.json` with mode 0600; keep this file out of source control. Durable worker event outboxes and registration identities are saved under `service-demo.workers/<run-name>/`. Ctrl-C stops the example workers; it does not delete registered resources or their logs. Each bootstrap run uses a new resource suffix and directory, so failed older registrations cannot select a stale worker identity.

In another terminal:

```bash
python client.py --config service-demo.json --target agent --key request-001
python client.py --config service-demo.json --target team --key request-002
python client.py --config service-demo.json --target workflow --key request-003
```

A request key identifies one logical submission. Reuse it after an uncertain response; use a new key for new work. Save the printed Session and Turn IDs. Stop only the client during execution, then reconnect:

```bash
python client.py --config service-demo.json --target team --session SESSION_ID --turn TURN_ID
python client.py --config service-demo.json --target team --session SESSION_ID --turn TURN_ID --snapshot-only
python client.py --config service-demo.json --target team --session SESSION_ID --turn TURN_ID --input 'Prioritize delivery dates' --key input-001
python client.py --config service-demo.json --target team --session SESSION_ID --turn TURN_ID --cancel --key cancel-001
python client.py --config service-demo.json --target team --session SESSION_ID --turn TURN_ID --command-id COMMAND_ID
```

The client renders a full snapshot first, resumes SSE from `as_of`, and reconnects from the last applied cursor. HTTP 410/cursor_expired reloads the snapshot. Disconnecting observation does not cancel execution. Command acceptance is separate from execution confirmation; inspect the command and Turn terminal state. `partial_succeeded` must be handled separately from `completed`. Capability discovery reports which commands the selected runtime currently accepts.

## Optional Managed member and approvals

Before bootstrap, set `AGENTSCOPE_MANAGED_AGENT_ID` to an active Managed Agent in the same namespace. Its runtime binding must be ready; executing it may incur model charges. Alternatively set `AGENTSCOPE_MANAGED_AGENT_JSON` to a complete valid `POST /api/v1/agents` request file with a Managed binding and definition; bootstrap creates it through the API. The default example does not fabricate provider credentials or model configuration.

Set `AGENTSCOPE_APPROVER_USER_ID` to a real platform user ID to append a Workflow approval node. Bootstrap adds that user as an Application viewer/approver. Once required_actions contains the request, use that user's **platform token**, not the application key:

```bash
export AGENTSCOPE_PLATFORM_TOKEN='DESIGNATED_APPROVER_PLATFORM_TOKEN'
python client.py --target workflow --session SESSION_ID --turn TURN_ID --human \
  --request-id REQUEST_ID --expected-version 1 --decision approved \
  --response '{"reason":"Reviewed"}' --key approval-001
```

Copy the actual version from the snapshot. Native Managed tool confirmations use `--response '{"allow":true,"reason":"Reviewed"}'`; external tool results use `--response '{"output":"result","is_error":false}'`. Only an authorized owner or delegated approver can confirm them. Workflow signals use the same request/version envelope with business JSON in `--response` and no decision.

## Connect real async frameworks

`AsyncInvokeAdapter` supports implementations exposing async `ainvoke`, including LangChain Runnables. Its input mapping is explicit, and its factory creates isolated instances per assignment:

```python
from agentscope_service import AsyncInvokeAdapter, instrument

adapter = AsyncInvokeAdapter(
    base, lambda ctx: build_your_runnable(),
    input_builder=lambda ctx: {"input": ctx.context["currentRequest"]},
    framework="langchain",
)
bridge = instrument(adapter, adapter=adapter, agent_key="my-runnable",
                    control_plane_http=base, transport="http", start_http=False,
                    internal_token=bootstrap_token)
```

For AgentScope Python, install AgentScope in the application environment and use its asynchronous Agent call:

```python
from agentscope.message import Msg
from agentscope_service import AgentScopeRunnerAdapter, instrument

adapter = AgentScopeRunnerAdapter(
    base, lambda ctx: build_your_agentscope_agent(),
    input_builder=lambda ctx: Msg("user", ctx.context["currentRequest"], "user"),
    result_mapper=lambda msg: {"answer": msg.content},
)
bridge = instrument(adapter, adapter=adapter, agent_key="my-agentscope-agent",
                    control_plane_http=base, transport="http", start_http=False,
                    internal_token=bootstrap_token)
```

The application supplies `build_your_*`, provider configuration and tool policy. AgentScope hooks preserve its observed events. Async invocation awaits cleanup before reporting cancellation. Runners must not swallow cancellation and continue executing. For custom streaming or safe additional-input boundaries, use `ExecutableAdapter` and `TaskContext.event()/refresh()` as in `worker.py`. Team leaders return `TaskResult(outcome="waiting")` after delegation or `TaskResult(complete_run=True)` after convergence; the bridge commits all pending events before the completion API. framework observation alone does not implement task execution.

HTTP commands are delivered at least once and may arrive out of order. The SDK fences attempts and caches terminal results, including cancellation before dispatch. HTTP response success is not an event commit: the durable event outbox drains only after eventAck. Arbitrary tool side effects still require business idempotency. Select `transport="grpc"` and `AGENTSCOPE_ASDP=host:port` only when using an ASDP-enabled deployment.

## API surface and frontend

`ManagementClient` covers Application and credential ownership, Agents, Teams, Workflow definitions/revisions, runtime policies, application target grants and credential management. `ServiceClient` covers capabilities, Session creation and Turn submission, snapshot, events, command status, input/actions/cancel/resume, usage, artifact downloads and webhook lifecycle.

Console's Session API panel uses the same TypeScript `ServiceClient` and `ServiceView` in `frontend/src/api/serviceSessions.ts`. Business frontends should keep API keys in their backend proxy. OpenAPI and event schema are in `agentscope-service/docs/service-api/`; checkpoint restoration is a Managed capability within the same Session API.

Read-only collaboration queries, including `TaskContext.refresh()`, retry transient connection failures, timeouts and HTTP 500/502/503/504 within the client's existing timeout budget. Authentication and business errors are returned immediately. Mutating requests are not automatically retried; use their specific idempotency contract when implementing application-level recovery.
