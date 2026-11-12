# Managed Agents internal protocol baseline

[中文](README.md)

Version **0.1.0**. Session Service runs inside the Agent Service JVM. Generic HTTP operations coordinate multilingual execution; AgentScope native commits retain the Java prepared profile. Existing Hands MQ encoding is retained and explicitly mapped; logical message examples are not broker bytes.

2026-10-30: Java now shares execution leases. Additions: `executions/release` expires only a matching fence, never completes work; `prepared/resources`; optional model purpose. `runtime/Notification` specifies Brain `/api/internal/brain/notify` hints (202 admitted, 429 full), not a Session Service route. prepared/query `resolve-input` resolves scoped file references on Service. Legacy coordination remains for compatibility; see the [implementation record](../testing/conformance/verification-java-platform-http-20261030.en.md).
2026-10-31: ModelConfiguration adds optional `requestTimeoutSeconds` (1–3600 seconds); parameters add `topP` (0–1), `parallelToolCalls` (boolean), and `toolChoice` (auto/none/required). All three adapters apply sealed values over native defaults; omission retains existing behavior. The latter two tool controls require an OpenAI-compatible API and are rejected for Anthropic bindings. See the [consistency implementation record](../../tests/general-agent-eval/consistency-implementation.en.md).


See the [layout/deployment design](../../docs/design/brain-runtime-layout-and-deployment.en.md) and [protocol rationale](../../docs/design/brain-session-hands-protocol.en.md). This directory owns the field, version, encoding and example baseline. Changes must update schemas, examples and validation together.

See the [implementation map](implementation-map.en.md) for actual profiles and limits, and the [regression baseline](../testing/conformance/README.en.md) for behavior checks.

## Files and validation

| File | Purpose |
| --- | --- |
| [openapi.json](openapi.json) | OpenAPI 3.1.1, generic operations and Java prepared handoff/coordination endpoints under `/internal/session-service/v0` |
| [messages.json](messages.json) | Five logical MQ channels, producers/consumers, schemas and ACK conditions; not physical topic configuration or AsyncAPI |
| [schemas/common.schema.json](schemas/common.schema.json) | Scope, ownership, native objects, content references and errors |
| [schemas/execution.schema.json](schemas/execution.schema.json) | Claims, renewals, durable deferral, Ready/Resume |
| [schemas/commit.schema.json](schemas/commit.schema.json) | Commits/receipts, input application, models, interactions, suspension, terminal state and public facts |
| [schemas/tools.schema.json](schemas/tools.schema.json) | Operations, commands, results, output and consumption receipts |
| [schemas/recovery.schema.json](schemas/recovery.schema.json) | Consistent recovery snapshots and reads |
| [preview.schema.json](schemas/preview.schema.json) | Online text interest and publication, separate from durable commits |
| [runtime.schema.json](schemas/runtime.schema.json) | Queue, pinned configuration, input and budget operations |
| [prepared.schema.json](schemas/prepared.schema.json) | Java prepared commit profile; numeric positions must fit the JCS safe-integer range |
| [coordination.schema.json](schemas/coordination.schema.json) | Closed AgentScope operations for processes, claims, inputs, queues, private ACKs, MCP metadata and child relationships |
| [hands-wire.schema.json](schemas/hands-wire.schema.json) | Actual Hands v2 envelope and decoded Java records |
| [examples/index.json](examples/index.json) | Positive fixture file, JSON Pointer and schema; all API requests/responses and channel variants have examples |
| [examples/negative-cases.json](examples/negative-cases.json) | Mutations of positive fixtures, distinguishing schema and semantic rejection |
| [examples/vectors.json](examples/vectors.json) | Shared Java/TypeScript/Python identity, canonical encoding and digest vectors |

Install into a separate development environment; validation then runs offline:

```sh
python3 -m venv /tmp/managed-agents-protocol-venv
/tmp/managed-agents-protocol-venv/bin/python -m pip install -r requirements.txt
make check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
```

Run from this directory with Python 3.10+ and Node.js 18+. The Node check installs no npm dependencies. `make check` validates schemas, OpenAPI, local references, positive/negative cases, handoff links and Python/Node vectors. `.invalid` schema IDs are locally resolved namespaces, not deployed websites.

Validate one message:

```sh
/tmp/managed-agents-protocol-venv/bin/python scripts/validate.py \
  --instance /absolute/path/message.json \
  --schema 'schemas/commit.schema.json#/$defs/CommitBoundary'
```

**These are contract checks, not runtime recovery tests.** They do not simulate databases, MQ, external tools or engines and do not establish implemented transaction/ACK correctness.

## Interfaces and service ownership

| operationId | HTTP path (all POST) | Contract |
| --- | --- | --- |
| `claimExecution` | `/executions/claim` | Durably claim eligible Ready/Resume work; the same claimId returns its original receipt, never another owner |
| `renewExecution` | `/executions/renew` | Renew only a live owner; the same renewalId returns its original expiry and cannot resurrect a lease |
| `deferDelivery` | `/deliveries/defer` | Persist deferral of the original Ready/Resume before ACK; does not release a running owner |
| `loadRecovery` | `/recovery/read` | One consistent full bundle or bundleRef; reads confer no write authority |
| `commitBoundary` | `/commits` | Commit native recovery position and platform changes; committed only, no 202 accepted |
| `getCommitReceipt` | `/commits/receipt` | Query original scope/Turn/commitId; 404 does not establish external nonexecution |
| `putNativeObject` | `/objects/write` | Validate bytes, size and digest; scope+objectId is idempotent for identical content and conflicts otherwise; upload authorizes no tool |
| `readNativeObject` | `/objects/read` | Read/verify scoped immutable references without arbitrary URL or cross-tenant access |
| `getToolState` | `/tools/state` | Accepted platform result or timestamped observation, not proof of external nonexecution |
| `previewInterest` | `/previews/interest` | Check the current owner and active reasoning call before querying subscriptions |
| `previewPublish` | `/previews/publish` | Validate the same identity and publish text best effort; no durable receipt or retry |

Reads also use POST to carry structured scope/object references uniformly; they remain read-only. Use platform service identity authorization; Bearer specifies transport, not JWT or another token format. Check scope, resource ownership and operation permissions server-side. executionToken is not an access credential. Tracing lives in HTTP/message transport metadata outside business digests.

The service sets claim/renewal expiry; expired receipts cannot authorize work. Retrying a successful claim/renewal does not create a new ownership/renewal period; a new period uses a new request ID. Capacity admission still precedes claims. Preserve database fencing, instance incarnation and takeover eligibility rather than trusting caller-provided owner fields.

Session Service initially runs inside Agent Service, owning platform commits, tool-event consumption and wakeups. Hands retains execution storage/result Outbox and queries original external executions itself. `getToolState` reads platform observations, not a substitute for Hands provider queries. Existing platform APIs continue input, withdrawal and cancellation admission; this contract adds no public SSE or cancellation API.

## Data, identity and digest rules

- Version is exactly `0.1.0`, distinct from current Java `SessionCommit.schemaVersion=1` and projection schema 3. Reject unknown protocol fields and version mismatches; do not silently reuse old semantics. Future integration requires explicit mapping of existing messages, not direct broker payload replacement.
- Scope contains tenant, namespace and sessionId. Counters, epochs, revisions and byte offsets use canonical decimal strings in 0..9223372036854775807 (positive types start at 1), without leading zeros. Native cursors are opaque and never numerically ordered.
- Protocol fields do not use null for deletion; omitted optional fields mean no update. `Content` selects exactly one of inline/ref. Inline is business/native JSON and may contain null, unlike protocol fields.
- Ordinary metadata requests/responses and decoded messages are limited to 1 MiB UTF-8; larger content uses ObjectRef. Objects are limited to 64 MiB decoded and object read/write JSON to 90 MiB. Tool output chunks are at most 16 KiB. Schema item limits also apply; implementations must bound actual transport bytes before parsing.
- References are complete, immutable and scope-bound, with SHA-256 over raw bytes. Temporary signed URLs may support authorized transport but are not durable references. Example checkpoints contain synthetic bytes, not usable AgentScope/PI state.

Business digests are **SHA-256 over RFC 8785 JCS UTF-8**, in lowercase hexadecimal. CommitReceipt.contentHash covers the entire CommitBoundary, including fence, commitId and expectedBrainRevision. EventReceipt.contentHash covers the entire ToolEvent. Transport headers/retry counters are excluded. Array order matters; Unicode is not normalized. Reject duplicate keys, non-finite numbers and invalid Unicode; represent exact large integers as strings. Reference: [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785).

Retry the original body with its original commitId. After ownership changes, query the original receipt; committed receipts remain readable, but changing the fence and reusing that ID conflicts. New recovery commits use new commitIds while preserving taskId/executionId.

Tool identity retains the [ManagedScope](../../common/src/main/java/io/agentcore/managed/server/runtime/managed/ManagedScope.java) derivation:

```text
frame(parts) = UTF8(concat(UTF16_length(part) + ":" + part))
fingerprint(parts) = lowercase_hex(SHA256(frame(parts)))
scopeKey = base64url_without_padding(SHA256(frame(tenant, namespace, sessionId)))
taskId = "tool_" + fingerprint(scopeKey, turnId, operationId)
executionId = taskId
execute.messageId = "execute_" + taskId
cancel.messageId = "cancel_" + taskId
resultId = "result_" + fingerprint(taskId, executionId)
event.messageId = "tool_event_" + fingerprint(scopeKey, taskId, executionId, sourceSeq)
replay.messageId = "replay_" + fingerprint(scopeKey, taskId, requestId)
```

UTF16_length matches Java String.length and JavaScript string.length, not UTF-8 byte length. The Adapter generates stable operationIds persisted with native recovery associations, never fresh randomness on restart. A nativeCallId may map to multiple operations; a batch operation may associate multiple nativeCallIds. `call1_0` is an example, not a required naming convention. Responsible producers mint and persist commitId, claimId, renewalId, deferralId and Ready/Resume messageId; redelivery retains them. Deduplicate by scope and messageId, never traceId.

## Commit, ACK and recovery semantics

Commit transactions validate scope/Turn, live ownership, expectedBrainRevision, native references, input/tool/result associations and authoritative cancellation state. An identical committed replay can return before revalidating its obsolete fence, but still requires authorization. Only Brain commits advance brainRevision, starting at 0 and incrementing once per new commit. Hands events, object uploads, projections and renewals do not advance it. Native persistence alone does not authorize tools; platform tasks and Outbox commit together.

`modelUpdates.dispatch` captures pre-call identity, recovery position and budget check/reservation; model dispatch waits for commit. Complete carries result references and either usage or usageUnknown, settling once by original model-call identity. Unknown usage is not zero. Interaction resolution and input application validate admitted inputs and original actions. Input/result applications commit with native recovery positions, without three additional Applied/Authorized journals.

The service also generates `model.started` / `model.completed` public facts from `modelUpdates`, projecting paired `span.model_request_start` / `span.model_request_end` events. Unknown usage omits public `model_usage`. `usage.inputTokens` is total input including cached tokens; `cacheReadInputTokens` / `cacheWriteInputTokens` are subsets and must not be counted twice. The adapter normalizes PI's disjoint input counters.

Adapters include `publicFacts` in the same commit that records an assistant message in native recovery state, using `item.completed` with a stable `item.message_id`. Only text is public; raw reasoning, tool arguments, and compaction summaries are excluded. Terminal commits can select a committed message through `terminal.messageId`: the identity is published once and included in final output. Omission uses a Turn-level fallback identity. Identities must not derive from transient commit IDs or text equality. See [public-projection.json](examples/public-projection.json). These are durable facts; online deltas use the separate channel below.

| Boundary | Success / ACK condition |
| --- | --- |
| Execution commit | Objects readable; recovery references, platform changes, Outbox and receipt committed |
| Execute delivery | Hands durably admits/claims; normal delivery starts external execution after ACK confirmation |
| Tool-event delivery | Durable receive/deduplication; out-of-order buffering does not itself accept results or wake Brain |
| Final result | Contiguous receipt through the result sequence and complete/explicitly gapped output; result and eligible Resume Outbox commit together |
| Ready / Resume delivery | Durable takeover or deferral of the original intent; validated stale/duplicate messages may finish, not merely a local Future |
| Native result application | Commit resultApplications only with recoverable native application; notification/receipt is insufficient |

EventReceipt describes consumption results; it is not another topic or a broker ACK body. Buffered means durable buffering of this event and requires empty acceptedResultIds. Applied means the contiguous consumed prefix covers this event and may also accept earlier buffered results. throughSourceSeq tracks that prefix independently of Brain log sequence.

ToolEvent sequences start at 1 and cover output, progress, gaps and the final result. Final output.throughSourceSeq equals its sourceSeq−1; no new source events follow the final result. Mode none means no recoverable output bytes; complete means full byte coverage; gapped requires persisted gapEventIds for this execution and validated offset coverage. Shape validation cannot replace these cross-message checks.

RecoveryBundle is a complete immutable snapshotId of data required to recover the current Turn, not necessarily the entire Session history. Large snapshots use bundleRef and still validate against RecoveryBundle after decoding. Do not mix snapshots or silently truncate required sets; reject excess sizes explicitly. Retain snapshots/objects while referenced by valid recovery points. Successful takeover consumes the corresponding wait eligibility in the platform transaction. A suspension retained in a Bundle is a recovery anchor, not permission to claim the same wait again.

Recover by claiming, reading a snapshot, restoring native state, reconciling original tasks/applications, then continuing or suspending. Query original receipts after commit timeouts; recheck readiness if results precede suspension; reconcile native records for uncertain application receipts. Query original executionId for unknown effects and retry only with explicit provider idempotency. Cancellation is a request; replacing Brain owners neither discards valid late Hands results nor automatically cancels them.

## Pinned run configuration identity

`executions/configuration` returns a `ConfigurationResponse` containing `protocolVersion`, `scope`, `turnId`, `configuration`, and required `identity`. Its `formatVersion` is `managed-effective-agent-v1`; it also carries the Session's bound `harnessType`, sealed `agentId` / `agentVersion`, and three lowercase hexadecimal SHA-256 digests:

- `sourceDigest`: RFC 8785 digest of the sealed `agentSnapshot`, distinct from the Control Plane's existing `definitionDigest`.
- `effectiveDigest`: RFC 8785 digest of the full definition after the common merger applies Session overrides.
- `configurationDigest`: RFC 8785 digest of the entire response after removing `identity.configurationDigest`. Models, tool references, permissions, input, configuration identity, scope, and Turn are all covered.

Service pins the entire response per Session / Turn and validates its Schema, digest, scope, Turn, and kernel binding when reading the cache. Worker restarts and changed deployment defaults do not recompile that Turn. PI / Codex workers verify the envelope before loading recovery or constructing native instances. Lease owner / epoch are excluded; worker inline-wait defaults apply only to local scheduling after verification.

This contract checks transport/cache consistency; service authentication and resource authorization remain the trust boundary. It does not establish that every definition field is mapped in all kernels. Unreleased development caches without `identity` are rejected rather than rebuilt during recovery; preserve original configuration and state for outstanding executions when updating Service / workers. AgentScope's prepared construction path still requires common-configuration acceptance. See the [configuration identity verification record](../testing/conformance/verification-configuration-20261029.en.md).

### Sealed AgentScope model selection

The `model-configuration` operation under `prepared/coordination` requires scope, turnId, numeric epoch, and execution token. Service validates the current execution and process lease before and after resolution, without holding a Session transaction lock during resolution. The `result` is a `ModelBindingResponse` using the identity and JCS digest above. Initial model selection and Session kernel binding are stored under the same Session lock in `ma.harness_model_configuration`. Generic PI / Codex configuration reuses this selection without resolving the same sealed definition twice.

Selection has two explicit modes: `binding` carries the full `model`; AgentScope-only `native` retains native named/default model behavior when both catalog and options are empty objects. Native mode seals the name or default selection, not ModelRegistry or deployment-default endpoints, parameters, and credentials. Unknown aliases in a nonempty catalog, malformed catalogs, and missing explicit credentials never silently fall back to native mode.

The AgentScope execution factory checks scope, Turn, kernel, Agent ID/version, and source/effective-definition digests before creating an independent model client. Explicit bindings currently map `openai-completions`, `openai-responses`, and `anthropic-messages`. Brain resolves credentials from `MANAGED_MODEL_` or compatible `PI_MODEL_` environment references; keys never enter configuration. Context windows, temperature, and output caps reach native calls; request-level options cannot change the model route or relax bound limits. Responses uses the official extension, maps `max_output_tokens` and `reasoning.effort`, and retains `store=false`. Unmapped Anthropic reasoning effort is explicitly rejected.

See [prepared-model.json](examples/prepared-model.json). Model selection is one part of shared configuration; independent child models, all definition fields, Control Plane capability admission, and platform pricing retain their implementation-plan acceptance requirements.

## Online text previews

`previews/interest` and `previews/publish` require service authentication plus full scope, turnId, fence, and modelCallId. Service checks the current lease, committed dispatch, reasoning purpose, and absence of a committed completion. Redis operations run outside the Session transaction lock. No subscriber, transport failure, or rejection stops only the preview, without changing model/tool execution.

Publication carries `messageId`, a decimal-string `sequence`, and 1–16 nonempty text chunks of at most 1024 Unicode codepoints each; requests are limited to 64 KiB. Initial sequence=0 emits start(0) and deltas(1…N); the next batch starts at N+1. Sequence counts public frames, not HTTP batches. TS/Rust clients send at most eight chunks per batch to fit worst-case JSON escaping, bound pending queues to 64 KiB and 256 chunks, use 250 ms request timeouts, and wait at most 200 ms at completion. Overflow or failure stops the preview without retries.

The message identity must match `item.message_id` / `terminal.messageId`. Both PI modes persist model-call identity in adapter-owned `managed_agents` message metadata; restored states without this field retain their native entry identity. PI publishes only main-answer events; PI Durable excludes complete/completeSimple auxiliary calls. Codex derives identity from a declared assistant message item ID and native thread ID, accepting only `response.output_text.delta`. Unknown identities or incomplete prefixes are not published. Executable native responses still require complete validation before reaching Core.

Service reuses existing text `event_start` / `event_delta`, epochs, frame ordering, and final-event reconciliation. Late subscribers cannot join mid-message. Duplicates, gaps, stale epochs, and slow consumers can stop a preview. No execution ledger, history, or durable MQ stores previews. SSE resume and delta replay are unsupported; final durable messages remain authoritative. See [preview.json](examples/preview.json) and the [preview verification record](../testing/conformance/verification-previews-20261029.en.md) for targeted coverage and remaining boundaries.

## Typical handoffs

| File / entry | Reading order and expectation |
| --- | --- |
| [tool-handoff.json](examples/tool-handoff.json) | claim → toolCommit/toolReceipt → execute → suspendCommit → resultEvent/resultReceipt → resume/takeover → recovery → applyResultCommit → finishCommit |
| Same file, `receiptQuery` | Lost commit response: retry original toolCommit or query commit1, obtaining the same toolReceipt without another task/authorization |
| [control-and-output.json](examples/control-and-output.json) | Renewal, durable deferral, cancellation with unknown outcome; independent s-output case receives final(4) first, then gap(3), delta(1), progress(2) before accepting the original result |
| [inputs-models-and-objects.json](examples/inputs-models-and-objects.json) | Object bytes/digests; input/model reservation, completion/interaction, answer, rejection, unknown usage, multiple operations and referenced recovery |
| [errors.json](examples/errors.json) | One response example per error code |

Files/branches are independent fixture scenarios, not a continuous script against one database. Tokens, configuration and objects are synthetic and must come from real identities, storage and engines during integration. The validator checks references, receipt digests and structure, not execution.

| Error code | HTTP | Retry original request with backoff? |
| --- | --- | --- |
| invalid_request | 400; 413 for excessive transport body | No |
| unauthenticated / forbidden | 401 / 403 | No |
| not_found | 404 | No; uncertain commits follow receipt resolution without another execution |
| ownership_lost / revision_conflict / identity_conflict | 409 | No; stop obsolete work, reload or resolve conflict |
| execution_busy | 409 | Yes |
| budget_exceeded | 409 | No; await budget conditions or user action |
| invalid_reference / unsupported_version / unsupported_capability | 422 | No |
| capacity_exceeded / temporarily_unavailable | 429 / 503 | Yes |

Only the three retryable errors include retryAfterMs. Unknown tool outcomes are ToolStateResponse observations, not retryable HTTP errors.

## Work division and remaining validation

- **Session Service:** implement OpenAPI, authoritative transactions, object APIs, event consumption and durable wakeups; preserve permissions, budgets and persistence guarantees.
- **Hands:** map messages; implement durable admission, contiguous output, cancellation/replay, original-execution lookup and result Outbox without interpreting native Context.
- **Brain / Adapter:** implement clients, native/operation associations, durability barriers, result-application reconciliation and fencing shutdown. Start with AgentScope Java, then PI; validate Codex capabilities before claiming support.

Focused transaction and runtime checks complement the offline contract checks. Integration must still validate database atomicity, lease/ACK races, uncertain effects, cancellation/late results, model/budget recovery and each engine's interception/reinjection capability. Runtime readiness requires mapping the existing implementation and passing those failure tests; schema success is not reliable-execution acceptance.


## PI recovery and delivery extensions (2026-10-28)

`recovery/read` optionally accepts `pageSize=1..16`; subsequent requests pass the previous `nextCursor` (afterRevision/throughRevision) as `cursor`. Service pins the native head revision and rejects changed heads or stale fences. Pages exceeding 512 KiB use `bundleRef`. Legacy requests without pageSize retain prior behavior. Result/input observations may advance during pagination; tool-ledger reconciliation remains required.

`deliveries/defer` accepts an optional fence. An active owner must match; unfenced requests may defer only deliveries without an active lease. Deferrals affect HTTP polling and the resume outbox without releasing ownership. The same deferralId returns its original receipt.

Sealed model configuration supports validated endpoint/API/credential environment names/token limits/pricing/sampling parameters and rejects credential values. See [PI deployment](../images/pi/README.en.md) for operations and encoding upgrade limits. Upgrade Service before using these optional extensions; legacy client requests remain unchanged.

Generic configuration also supports a model-alias catalog and `MANAGED_MODEL_` credential references, retaining `PI_MODEL_` compatibility. Nonempty catalogs reject unknown aliases and seal the complete selected binding with the Turn; do not inherit defaults from another provider. Service and Adapters validate output/context limits and reasoning consistency; Codex accepts only Responses. This currently applies to the generic PI / PI Durable / Codex path; AgentScope prepared integration and binding-based pricing remain outstanding. See [Codex model bindings](../rust/README.en.md) for mappings/deployment variables and the [verification record](../testing/conformance/verification-model-bindings-20261029.en.md).
