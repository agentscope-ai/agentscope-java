# Agent interaction: Managed Agents text profile v1



中文：[中文协议](agent-protocol_zh.md).

## 1. Source of truth

Use Claude **Managed Agents**, not Messages API streaming. Do not invent `turn.finished`,
`tool.completed` or a generic `data` envelope. The upstream beta is `managed-agents-2026-04-01`;
our explicit subset is `managed-agents-text-v1`. Store that profile, session association, case,
trial/attempt IDs, versions and configuration in evaluation binding/manifest files, not event payloads.
Do not assume every event contains a session ID: the owning stream supplies that association.

Pin the SDK commit and type-file blob hashes in [upstream.json](../schemas/upstream.json).
The [JSON Schema 2020-12 file](../schemas/managed-agents.schema.json) defines the supported subset,
not full API/SDK compatibility. The pinned SDK includes `retries_exhausted` and `refusal` idle reasons
beyond the shorter guide's summary; preserve their actual semantics.

## 2. Setup and input

Setup is separate from event submission. The manifest pins agent implementation/version/image,
native profile, model/credential reference, workspace fixture hash, business tools/network access,
capabilities and time/turn limits. Trusted executors resolve credentials. Hidden tests, answers,
scoring rubrics and selection rationale are not agent inputs. Adapter capability descriptions are
configuration receipts, not fabricated Anthropic events.

```json
{"events":[{"type":"user.message","content":[{"type":"text","text":"Fix empty input while preserving the function signature."}]}]}
```

Send parameters have no server `id` or `processed_at`. Supported inputs:

| Type | Fields |
| --- | --- |
| `user.message` | text-only `content` |
| `user.interrupt` | optional `session_thread_id` |
| `user.custom_tool_result` | `custom_tool_use_id`, optional `content` and `is_error` |
| `user.tool_confirmation` | `tool_use_id`, `result` allow/deny; `deny_message` only with deny |

Reject unsupported `system.message`, outcomes, self-hosted `user.tool_result`, image/document/search
blocks and workflows explicitly. Never downgrade them to user text. Later support requires native
capability checks and official schemas. Send only new messages each turn; preserve native session
history instead of simulating resume by restarting and replaying a transcript.

## 3. Persisted events

```json
{"id":"sevt_answer","type":"agent.message","processed_at":"2026-11-07T12:00:00Z","content":[{"type":"text","text":"Updated."}]}
{"id":"sevt_idle","type":"session.status_idle","processed_at":"2026-11-07T12:00:01Z","stop_reason":{"type":"end_turn"}}
```

Input history echoes have IDs and optional/null `processed_at` while queued. Model, agent and
session events require IDs, RFC3339 processing timestamps and exact types. Do not substitute a
client receive timestamp for a native occurrence time.

The profile supports agent messages (text/redacted), user echoes, running/idle/rescheduled/terminated
session states, builtin/MCP/custom tool use and their results, thinking progress without content,
thread compaction, model-request start/end, and `session.error` with `unknown_error` only.
Raw native error details remain in a separate artifact. Permission events support
`evaluated_permission=allow/ask/deny/null`; detailed `evaluation` objects are deferred.

MCP uses `mcp_server_name` and `mcp_tool_use_id`; builtin results use `tool_use_id`; custom results
use `custom_tool_use_id`. These references are not interchangeable. Do not forward already-executed
internal tools for external execution again. Only emit observed spans, compaction and thinking
signals. Disclose missing observability in the binding; thinking carries no reasoning text.

The profile consumes one thread stream with sequential model requests, not merged multi-thread/workflow
streams. Reject unsupported types/fields rather than silently dropping them; extend the profile
and contract tests deliberately when an adapter needs more of the upstream schema.

## 4. Lifecycle semantics

`end_turn` includes natural completion and interruption, not verifier success. `requires_action`
lists blocking call IDs; resolve each independently. Results may arrive before the idle echo, so
an already-resolved call must not become pending again. A duplicate result must not execute another
side effect. The profile reader records facts and references; it does not execute any tool or resume work.
Wait for actual runtime status events after resolving pending actions.

`budget_reached` is monetary, not a timeout/turn limit. Preserve `retries_exhausted` and `refusal`.
An enqueued interrupt is not yet an applied cancellation. Use its processing timestamp, later
status and process evidence; store timeout/cancelled/worker-lost/indeterminate execution conclusions
in the result sidecar, not invented stop reasons. `error.retry_status` is an object with type
retrying/exhausted/terminal; an error alone does not imply session termination. Scoring is independent.

## 5. Streaming, previews and replay

Use SSE on a streaming transport and complete JSON objects per LF-delimited line in archives.
Do not split records at U+2028/U+2029 inside strings. The SSE reader handles comments, multi-line
data and CRLF, and discards an unfinished frame at disconnect.

```json
{"type":"event_start","event":{"type":"agent.message","id":"sevt_answer"}}
{"type":"event_delta","event_id":"sevt_answer","delta":{"type":"content_delta","index":0,"content":{"type":"text","text":"Up"}}}
```

Previews have no own ID/timestamp. Accumulate by `(event_id,index)`; the final message replaces
the preview instead of appending again. Deltas may cover only a prefix, and interruption may prevent
any final message. Clear unfinished previews at model-request end or disconnect. Thinking has a
start preview but no text deltas. Previews stay out of the canonical event history; raw transport
captures can be archived separately.

Open/buffer the live stream, fetch all paginated history, then tail live events with ID deduplication.
Conflicting payloads under the same ID fail. User `processed_at` may move from null/absent to a timestamp;
stale queued echoes cannot revert it. `EventProjection` implements reconciliation, not HTTP connections,
pagination, automatic reconnect, durable request idempotency or a workflow state machine.

## 6. Usage and evaluation records

Model-span end requires input/output/cache-creation/cache-read token counts. Never invent zeroes to
satisfy this schema. If an adapter cannot observe the full record, omit the standard model-span pair
for that request and retain partial metrics with their provenance in evaluation observations; do not
leave an artificial open span or fabricate its end. `session.usage` is deferred
because it carries cumulative pricing semantics. Never sum cumulative snapshots with per-request usage.

Keep event mapping/provenance, capabilities and actual versions in bindings; diffs and resource metrics
in artifacts; independent scores and scoring errors in score records. Unknown metrics are null. Execution
status, scoring validity and reward are separate. This in-memory reader does not guarantee input
idempotency, exactly-once effects, durable event storage or crash recovery; later phases require an
execution ledger and stable native ID mapping. Do not retry unknown side-effect outcomes automatically.

## 7. Local acceptance

From the repository root, preferably with a `benchmarks/harness/.venv` environment:

```sh
python3 -m pip install -r benchmarks/harness/requirements-protocol.txt
python3 -m unittest discover -s benchmarks/harness/tests -p 'test_*.py' -v
python3 benchmarks/harness/eval.py protocol-smoke --output benchmarks/harness/artifacts/protocol-smoke
python3 benchmarks/harness/eval.py protocol-check /path/to/events.jsonl
python3 benchmarks/harness/eval.py protocol-check /path/to/events.sse --format sse
python3 benchmarks/harness/eval.py request-check /path/to/send-request.json
```

Output directories must not exist; prior evidence is never overwritten. Smoke replays a fixture,
does not call a real model, and is neither an agent capability score nor an execution-backend acceptance test.
Regression tests cover queued updates, replay conflicts, previews, early custom results, tool reference
types, interruption, error retry semantics, invalid fields, SSE framing and Unicode.

## 8. References

- [Events and streaming](https://platform.claude.com/docs/en/managed-agents/events-and-streaming)
- [Preview deltas](https://platform.claude.com/docs/en/managed-agents/event-deltas)
- [Event catalog](https://platform.claude.com/docs/en/managed-agents/reference)
- [Pinned SDK types](https://github.com/anthropics/anthropic-sdk-python/tree/4c7b598466b88559583f98216139be8e9b34c1fc/src/anthropic/types/beta/sessions)

Profile changes update schemas, both language guides, provenance and positive/negative tests together.
