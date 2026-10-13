# Control Plane Python SDK

Integration SDK for AgentScope Service: framework observation, session events, application contracts and collaboration clients.

Install the `agentscope-service-sdk` version listed in the Service release manifest, or install a downloaded wheel:

```bash
python -m pip install ./agentscope_service_sdk-VERSION-py3-none-any.whl
```

Python 3.9+ is required by the package metadata. grpcio and protobuf constraints are declared in `pyproject.toml`. Optional OpenClaw WebSocket support is available through `agentscope-service-sdk[openclaw]`.

## Choose a transport before connecting

Standard Service deployments support outbound HTTP execution: `instrument(..., control_plane_http=base, transport="http")`. The worker registers using its bootstrap credential, then uses its scoped registration credential at `/api/v1/agent-runtime/exchange`. Commands are acknowledged after acceptance; event batches retain their durable journal until the separate event ACK. No inbound worker port is needed; set `start_http=False` when the optional observation contract server is not used.

`transport="grpc"` selects ASDP for deployments with a reachable gRPC listener; pass `control_plane="host:port"`. The legacy parameter name `start_grpc=False` disables either selected push transport and its advertised execution/reporting capabilities. It is only useful for standalone observation. Do not use an HTTP port as a gRPC address.

`ServiceClient` provides snapshot, paginated events, SSE, capabilities, commands, actions, usage, artifacts and webhooks. `ManagementClient` provides Applications, Agents, Teams, Workflow definitions, runtime policies and application credentials. Management uses a platform token; invocation uses an application API key or an authorized platform token. API keys require an Application, explicit target grants, and scopes. Use `create_session(target)` followed by `submit(session_id, ..., idempotency_key=...)` for Agents, Teams, and Workflows.

See `../../examples/service-api/README.md` for a complete API-only bootstrap and reconnect/approval flow. `AsyncInvokeAdapter` and `AgentScopeRunnerAdapter` execute fresh framework instances with explicit input mapping. Automatic framework detection selects observation adapters, not execution runners.

## Develop

```bash
python -m pip install -e '.[dev]'
python -m pytest
python -m build
```

Licensed under Apache-2.0; see `LICENSE`.

Event journal records are limited to 16 MiB. HTTP batches target 16 MiB and requests allow 32 MiB; gRPC messages allow 32 MiB. Oversized execution events raise explicitly rather than truncating. Store large tool output as an artifact and emit its reference.
