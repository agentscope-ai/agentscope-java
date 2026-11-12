# Control Plane Python SDK

Managed Session Draft 3 uses one `/v1/sessions/{id}/events` interaction endpoint.
Use send_events/sendEvents, submit_turn/submitTurn, steer, inject and cancel helpers. Read final output
independently through output/final_output/finalOutput. Listing, lifecycle, snapshots, files, tool replies
and reliable resume are not enabled in the first release; consult capabilities before optional APIs.
Source protocol and pinned SDK verification: `managed-agents/tests/session-protocol/`.


Integration SDK for Managed Agents: framework observation, session events, application contracts and collaboration clients.

Install the `managed-agents-sdk` version listed in the Service release manifest, or install a downloaded wheel:

```bash
python -m pip install ./managed_agents_sdk-VERSION-py3-none-any.whl
```

Python 3.9+ is required by the package metadata. grpcio and protobuf constraints are declared in `pyproject.toml`. Optional OpenClaw WebSocket support is available through `managed-agents-sdk[openclaw]`.

## Choose a transport before connecting

Managed execution uses the native `/v1/sessions` client, sharing the original credentials
and bounded transport with `ServiceClient`:

```python
from managed_agents import ServiceClient

managed = ServiceClient(base_url, api_key=api_key, tenant=tenant, namespace=namespace).managed_sessions()
session = managed.create_session({"agent": agent_id, "environment_id": environment_id}, idempotency_key="create-001")
turn = managed.submit(session["id"], message="Review the project", idempotency_key="turn-001")
receipt = managed.input(session["id"], turn["id"], "steer", {"message": "Check tests first"}, idempotency_key="steer-001")
applied = managed.input_receipt(session["id"], turn["id"], receipt["id"])
snapshot = managed.snapshot(session["id"])
for event in managed.stream(session["id"], after=snapshot["as_of"]):
    # Apply and persist event["cursor"]; filter data.turn_id for one Turn.
    pass
```

Native Inputs are steer/inject/reply/cancel. Lifecycle operations also require an explicit
idempotency key; query `lifecycle_receipt` for cleanup completion. Files, explicit Artifact
publication, budget, usage and pinned pages are available. Turn snapshot pages use the
Session resources route with their snapshot ID; usage retains `data/as_of`. Fork/checkpoint
restoration are still in progress. Native calls do not fall back to legacy routes.
The flat client described below remains for other execution families.

Standard Service deployments support outbound HTTP execution: `instrument(..., control_plane_http=base, transport="http")`. The worker registers using its bootstrap credential, then uses its scoped registration credential at `/api/v1/agent-runtime/exchange`. Commands are acknowledged after acceptance; event batches retain their durable journal until the separate event ACK. No inbound worker port is needed; set `start_http=False` when the optional observation contract server is not used.

`transport="grpc"` selects Runtime Protocol for deployments with a reachable gRPC listener; pass `control_plane="host:port"`. The legacy parameter name `start_grpc=False` disables either selected push transport and its advertised execution/reporting capabilities. It is only useful for standalone observation. Do not use an HTTP port as a gRPC address.

`ServiceClient` provides resource snapshots, keyset lists, paginated events, SSE, capabilities, commands, actions, authoritative usage and artifacts. Session Webhooks support registration, keyset subscription/receipt lists, deletion and explicit retry. Managed budget reads and settings use Agent Service; `set_budget(session_id, limits, revision=current_revision)` prevents lost updates with `If-Match`. Settings return 202 and apply before the next model call; GET distinguishes desired and applied revisions. Checkpoint listing uses `checkpoints(session_id, after=..., through=..., limit=20)`; keep its `as_of` watermark and advance `next_cursor` while `has_more`, including empty pages. Raw recovery state stays private; `restore_checkpoint`/`fork` writes return durable 202 commands; poll `command` for completed/rejected. Both roots require interact permission and settled execution/inputs. `ManagementClient` provides Applications, Agents, Teams, Workflow definitions, runtime policies and application credentials. Management uses a platform token; invocation uses an application API key or an authorized platform token. API keys require an Application, explicit target grants, and scopes. Use `create_session(target)` followed by `submit(session_id, ..., idempotency_key=...)` for Agents, Teams, and Workflows.

Check `client.capabilities(session_id)["capabilities"]["file_input"]` before sending file references.
`client.export_events(session_id, through=snapshot['as_of'], limit=1000)` iterates
fixed-watermark public NDJSON windows without buffering the complete history.
Persist each applied event cursor and pass it as `after` when resuming; retain the
same `through`. Incomplete records and count mismatches raise explicitly. Close
the iterator when stopping early. Individual export events have a 1 MiB client limit.

Managed Sessions accept `upload_file(...)`, then structured `file_id` blocks in Turn input. Uploads
default to 16 MiB; one input resolves at most 128 references and 64 MiB in total.
`publish_artifact(session_id, {"file_id": file_id}, idempotency_key=...)` explicitly publishes a
deliverable. `artifacts(session_id, turn_id, after=..., snapshot_id=..., limit=...)` lists a bounded
snapshot page; omit `turn_id` for Session artifacts. `artifact_metadata(...)` reads metadata and
`artifact_content(...)` downloads owned bytes through `/content`, with a 100 MiB client bound.
`artifact(session_id, turn_id, artifact_id)` is the Turn download shorthand. External HTTPS references
remain references; Service does not fetch them. `delete_artifact(...)` removes the current record,
retains the uploaded file, and leaves old snapshots readable. A deleted publication's original
idempotency key replays its original receipt; use a new key to publish again.

See `../../examples/service-api/README.md` for a complete API-only bootstrap and reconnect/approval flow. `AsyncInvokeAdapter` and `AgentScopeRunnerAdapter` execute fresh framework instances with explicit input mapping. Automatic framework detection selects observation adapters, not execution runners.

## Develop

```bash
python -m pip install -e '.[dev]'
python -m pytest
python -m build
```

Licensed under Apache-2.0; see `LICENSE`.

Event journal records are limited to 16 MiB. HTTP batches target 16 MiB and requests allow 32 MiB; gRPC messages allow 32 MiB. Oversized execution events raise explicitly rather than truncating. Store large tool output as an artifact and emit its reference.

Session/Turn lists use `after`, not offsets. A snapshot returns `resources[kind]` finite pages,
`as_of` and `snapshot_id`; use `resources(..., after=..., snapshot_id=..., turn_id=...)` to continue.
Actions use `{ "answers": [...] }` for every backend; command receipts distinguish transport
`status` from native `command_status`/`input_status` and are read at the Session command route.
JSON responses are bounded at 16 MiB and each SSE event at 1 MiB. Unsupported snapshot, capabilities
or event schemas fail explicitly instead of being interpreted as an earlier Service version.

`trace(session_id, after=0, through=..., limit=16)` reads a fixed native prefix only when the server enables it and the human caller has a current trace grant. Continue using `next_seq` and the same `as_of_seq`. Application keys cannot use it.
