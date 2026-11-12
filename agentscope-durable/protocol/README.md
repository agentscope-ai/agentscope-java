# Durable v1 / 通用协议与 Java 契约

## Authority and packaging / 权威来源与打包

This directory is the centralized protocol source; it is not an additional Maven module.
The `durable-runtime` JAR packages the JSON Schema and shared fixtures under
`durable-protocol/`. Markdown files are documentation only, not runtime resources.

本目录集中维护权威协议，不新增 Maven 模块。`durable-runtime` JAR 将 JSON Schema 与
共享 fixture 打包到 `durable-protocol/`；Markdown 仅用于文档，不作为运行时资源。

| Source / 来源 | Purpose / 用途 |
| --- | --- |
| [schema.json](schema.json) | Executable wire validation / 机器可执行的格式校验 |
| [vectors.json](vectors.json) | Canonical digests, identities and invalid inputs / 规范化摘要、身份与非法输入 |
| [projection-vector.json](projection-vector.json) | Message/tool projection and replay / 消息、工具投影与回放 |
| [user-workflows.json](user-workflows.json) | Shared user-operation fixtures / 共用用户操作流程 fixture |

## Public interaction specification / 对外交互规范

User operations, messages, events and replay form one language-neutral contract.
Start with the [public interaction contract](public-interaction-contract.en.md) for
Session / Thread / Turn operations and conformance requirements. The
[execution model design](../docs/design/execution-model-plan.en.md) records design decisions;
the [acceptance backlog](../docs/verification/execution-model-acceptance.en.md) records
remaining verification. A documented schema or fixture is not a passing acceptance claim.

用户操作、消息、事件与回放构成同一语言中立契约。
[对外交互规范](public-interaction-contract.md) 是 Session / Thread / Turn 操作与一致性要求的入口。
[执行模型设计](../docs/design/execution-model-plan.md) 记录设计决策，
[独立验收清单](../docs/verification/execution-model-acceptance.md) 记录待验收项；
Schema 或 fixture 的存在不等于验收通过。

The sections below describe wire envelopes, Java boundaries, events, queued execution,
and frozen tool/MCP recovery contracts. See the [library README](../README.en.md) for
module layout and current build instructions.

下文说明 RPC 信封、Java 边界、事件、队列执行与冻结工具/MCP 恢复契约。
模块布局与当前检查入口见[库 README](../README.md)。

## Frozen tools and optional MCP recovery / 冻结工具与可选 MCP 恢复

`ToolBinding` contains name, definition version, executor route, schema and a private target.
Its canonical digest is carried in `ToolCall.binding`; null is reserved for direct unbound
Executors. Catalog-backed Workers verify the same binding before dispatch. Targets contain
credential references rather than resolved secrets. Brain schema registration and Hands target
resolution use the same definition. The Worker ledger and queued transport layouts are format 3.

`ToolBinding` 包含名称、定义版本、Executor 路由、schema 和私有目标；其摘要进入
`ToolCall.binding`。直接使用无目录 Executor 时可为 null；目录型 Worker 派发前必须校验。
Brain 注册 schema 与 Hands 解析目标使用同一份定义，目标保存凭据引用而非解析后的密钥。
Worker 账本和队列传输存储格式为 3。

MCP recovery is an opt-in Durable extension, not a guarantee of ordinary MCP. A remote tool
advertises `_meta["io.agentscope.durable/execution"]`:

```json
{"version":1,"idempotentStart":false,"queryTool":"execution_status","cancelTool":"execution_cancel"}
```

`cancelTool` is optional. The query tool must advertise `annotations.readOnlyHint=true`.
Discovery freezes the main descriptor and all control descriptors. New dispatch verifies them;
existing operations can still use their unchanged control descriptors when the main tool changes.
Metadata propagation must be enabled. Start receives an execution identity under the same `_meta`
key; query/cancel receive that identity as arguments and metadata. The identity is
`{version:1, executionId, requestDigest}`. The external execution ID is the canonical hash of
`[namespace, durableExecutionId]`; requestDigest hashes the complete frozen ExecutionRequest.
The remote service must reject the same identity with different input, preserve operation
identity across restarts, and define a retention window covering the advertised recovery period.
`idempotentStart=true` additionally guarantees repeated admission never starts another effect.

MCP 恢复是显式选择的 Durable 扩展。查询工具须声明只读，发现时冻结主工具及控制工具描述。
首次执行校验这些描述；原操作可在主工具变化后继续使用未变化的控制工具。必须开启元数据
透传；启动元数据、查询/取消参数使用上述稳定身份。远端须拒绝同身份不同请求，跨重启保留
执行身份，并声明覆盖恢复周期的保留窗口；只有重复受理绝不新增副作用时才能声明幂等启动。

Start/query/cancel return a normal MCP CallToolResult whose `structuredContent` conforms to
`mcpExecutionObservation` in schema.json: identity fields plus state and optional result/error.
SUCCEEDED/FAILED require a nested MCP result (content array, isError matching the state).
Other states have no result. A top-level MCP error, wrong identity, invalid contract or lost
reply is unconfirmed, not a terminal execution failure. NOT_FOUND may trigger another start
only with the frozen idempotentStart guarantee. A pending cancellation remains PENDING/RUNNING
until the provider confirms a terminal outcome. No callback URL or credentials are transmitted.

启动、查询和取消的 MCP 返回值在 structuredContent 中承载身份、状态及可选结果/错误。
成功/失败必须携带对应的原生 MCP 结果；其他状态不携带结果。顶层 MCP 错误、身份不匹配、
协议损坏或丢回包均不能证明执行失败。只有声明幂等启动时 NOT_FOUND 才可触发重新受理。
取消已受理但未确认停止时仍返回 PENDING/RUNNING，不伪造 CANCELLED。
Java 边界使用同一 JSON Schema 校验；不传递回调地址或凭据。

## RPC envelopes and validation / RPC 信封与校验

All RPC routes accept POST JSON:
`{"version":"durable.v1","operation":"submit","payload":{...}}`.
Responses contain `version` and `result`, or `code`/`message` on non-200 status.
Clients preserve stable request/commit/execution IDs when a response is uncertain.
The request limit is 16 MiB. `schema.json` defines every Backend/Executor request
and response, domain records, native checkpoint metadata and typed public events.
Java `DurableProtocol` validates the same schema at HTTP boundaries; the shared
Backend additionally validates commits/events and enforces transaction semantics.
Unknown fields, missing required fields, wrong types and unsupported operations
are rejected. A malformed successful reply is `UNAVAILABLE`: it cannot prove
that a write failed. Query the original receipt before repeating the command.

所有 RPC 使用上述 POST 信封。响应不确定时保留原 request/commit/execution ID。
请求上限 16 MiB。Schema 完整定义操作请求/响应、领域对象、恢复元数据和事件载荷。
Java `DurableProtocol` 在 HTTP 边界校验；共享 Backend 校验提交/事件并保证事务语义。
多余字段、缺字段、错误类型和未知操作均拒绝。损坏的成功响应视为 `UNAVAILABLE`，
写入结果仍不确定，必须查询原回执。此协议不依赖 HTTP、AgentScope 原生类或平台 DTO。

## Java interfaces / Java 接口

`DurableTypes` defines Session/Thread/Turn/Run/Step/Lease/Commit/Signal/Execution/Event records;
`DurableMessages` defines typed Input, Message, Content, Source and Artifact records.
`DurableProtocol.Request`, `Response` and `Error` provide the wire envelopes.
`HarnessAdapter` owns native interpretation; `SessionBackend` owns durable facts;
`Executor` owns independently queryable side effects. Runtime coordinates those APIs.

`DurableTypes` 是状态、恢复与 Brain–Hands 的 Java 类型契约；`DurableMessages` 是
公共输入、消息、媒体源和制品引用契约。原生格式仅由 HarnessAdapter 解释。
SessionBackend 管持久事实，Executor 管独立执行，Runtime 通过这些接口协调。

| Backend operation | Payload |
| --- | --- |
| capabilities | empty object; returns actual storage guarantees |
| create | definition: SessionSpec(key, primaryBinding, metadata); returns SessionView and creates the primary Thread atomically |
| session / closeSession | session |
| createThread | definition: ThreadSpec(key: ThreadKey, binding: ThreadBinding) |
| thread / closeThread | thread: ThreadKey(session, threadId) |
| threads | session, cursor, limit |
| submit | thread, requestId, input |
| admission | session, requestId; returns the original receipt including Thread identity |
| sessions | after (storage key or null), limit |
| ready | harnesses: HarnessSelector[], limit; exact definition/state or both null for a resolver |
| turn | thread, turnId |
| prepareRun | lease, requestId; idempotent preparation of one native invocation |
| run / runs | thread, turnId, runId / cursor, limit |
| step / steps | thread, turnId, runId, stepId / cursor, limit |
| claim | thread, owner, duration (ms) |
| renew / release | lease, duration / delay (ms) |
| frame | lease |
| commit | lease, commit |
| receipt | thread, turnId, commitId |
| execution | thread, executionId (private persisted intent read) |
| delegate | thread, request: ExecutionRequest, target: DelegationTarget; atomic child admission and mapping |
| delegation | thread, request; returns the saved mapping or null |
| cancelDelegation | thread, request, childTurnId (nullable); persists cancellation without creating work |
| beginDispatch | lease, executionId |
| acceptResult | thread, executionId, result |
| signal | thread, turnId, signal: id/key/value |
| cancel | thread, turnId, requestId |
| snapshot / events | session and optional EventFilter; events adds cursor, limit |
| pruneEvents | session, throughCursor |

Commit fields include run (a structured invocation transition), steps (ordered Step transitions),
commitId, expectedRevision, checkpoint, tools, appliedResults,
consumedSignals, events, status, waiting, output, error. Null and empty lists are
explicit. A digest is SHA-256 of RFC 8785 canonical JSON `{"value": command}`.
Execution ID is `exec_` + the same hash of `[thread, turnId, callId]`.
ToolCall.origin is an explicit nullable `{runId, stepId}` reference to the original intent. It participates
in the request digest and remains stable through redelivery and recovery. Applied results are objects
`{executionId, appliedAt}`; the application reference is committed with the checkpoint and may identify a
later invocation. Active invocations require a position; host settlement outside an invocation uses null. ToolExecution and public
tool projections retain both positions; acceptance alone never marks a result applied.

ToolCall.origin 显式记录最初 Run/Step，纳入请求摘要；无调用记录的自定义执行可为 null。
恢复和重投保留原始来源。appliedResults 使用 `{executionId, appliedAt}` 对象，应用位置与 checkpoint
原子提交，可能属于后续 Run/Step；活跃调用的位置必填，调用之外的宿主结清使用 null。执行视图与公共工具投影同时保留
两个位置，接收结果不代表已经应用。
Native checkpoints are opaque to the Runtime and never appear in public snapshots.
JSON must contain exactly one value, without duplicate object keys. Numbers must survive
canonical IEEE-754 serialization without changing their decimal value; exact integers
or decimals outside that range must be strings. `vectors.json` contains common digest,
execution-command identity and invalid JSON/number fixtures for independent implementations.

JSON 只能包含一个根值，不能重复对象字段。数值规范化后不能改变其十进制值；
不能无损表示的精确大整数或小数使用字符串。共用 fixture 包括摘要、执行命令身份及非法输入。

A HarnessSelector is `{harness, definitionVersion, stateVersion}`. Set both version fields
to null only when a resolver accepts that Harness's definitions. Static registrations
query an exact indexed version pair before applying the result limit; unrelated versions
cannot starve supported work. An incompatible host does not put a Turn in `runtime.error`.
The Java transactional Session layout is format 11, including resource lifecycle history and Thread state inside the Session transaction; prior unreleased layouts are rejected,
not silently upgraded or retained as a compatibility path.

HarnessSelector 精确选择 Harness、定义和状态版本；动态解析器可将两个版本字段都设为 null。
后端先按版本索引筛选再限制数量。不兼容宿主不会把其他宿主可恢复的任务转入错误等待。
Java Session 存储格式为 11，包含资源生命周期历史，Thread 状态仍在 Session 短事务内提交，不提供旧开发版格式的自动迁移或兼容分支。

`Frame.checkpointMetadata` binds a non-null checkpoint to schema version `1`,
Harness, definitionVersion, stateVersion, a decimal-string position and its canonical
SHA-256 digest. Position advances only when native state changes. Java checks binding
and digest before native recovery. Missing/incompatible metadata is not silently
migrated; corrupt state is rejected. A committed native checkpoint cannot be erased
by a null checkpoint. Private Frames are available only through fenced worker access.

`Frame.checkpointMetadata` 将非空原生状态绑定到版本、Harness、定义版本、状态版本、
独立的字符串位置与规范化摘要。位置只随原生状态变化；Java 恢复前检查绑定和摘要。
缺失/不兼容元数据不自动降级，损坏状态拒绝恢复，已有原生状态不能被 null 擦除。
Frame 是受租约保护的内部工作接口，不是公共读取视图。

## Input and events / 输入与事件

AgentScope accepts a plain string or `{"schemaVersion":"1","content":[...]}`.
Content is `text` with text, or `image`/`audio`/`video` with a source. Source is
`{"type":"url","url":"...","mimeType":"..."}` (mimeType optional) or
`{"type":"base64","mimeType":"...","data":"..."}`. Arbitrary native objects
are rejected. Final Turn output is a `Message` with schemaVersion, messageId, role
and content. Native reasoning and private metadata are excluded.

AgentScope 支持字符串或带版本的结构化 content，支持文本、图片、音频和视频及 URL/base64 源。
不再将任意原生 JSON 字符串化成提示词。最终 Turn 输出为公共 Message，不携带原生推理与私有元数据。

Reserved event families include Turn lifecycle/wait/resume, `signal.accepted`,
`message.delta`/`message.completed`, tool request/dispatch/progress/result/application,
and `artifact.published`. Each has a schema-constrained payload. Application events
use `x.<namespace>.<name>` with an object payload. `tool.progress` records changed
nonterminal observations, including UNKNOWN; it is not a terminal success.
Artifact URIs and retention are host-owned; publishing a reference does not upload it.

保留事件覆盖 Turn 生命周期/等待/恢复、信号、消息、工具进展/结果/应用和制品引用，
均有载荷约束。自定义事件使用 `x.<namespace>.<name>`。UNKNOWN 仍是未确认结果。
制品引用由宿主管理，发布引用不代表上传制品。

Snapshots contain session, turns, projection, sequence (string, including "0") and
cursor. `EventProjection` resets from this snapshot, ignores sequences already
applied and requests a snapshot on gaps. Messages are keyed by messageId: newer
attempt/revision replaces older text, a completion replaces accumulated deltas and
late deltas cannot append to that completed revision. Tool facts merge by the logical call key
`tool:` + hash([threadId, turnId, callId]), using the DurableJson canonical digest. Native call
arguments/updates/progress and Hands execution facts share a view; only external executions carry
executionId. Views retain their first sequence, Thread/Turn and available Run/Step associations.
`projection-vector.json` includes sequences above 2^53. Java consumes it; other language
implementations must adapt to this contract before claiming conformance.

AgentScope projects separate assistant messages for distinct reasoning rounds within a Turn.
Logical message IDs survive Run/Step changes. A new model request increments attempt; replaying
a committed response retains attempt and replaces partial presentation using revision. Text deltas
come from middleware-processed native presentation facts in the same journal transaction. A final
message replaces provisional text and adds structured content. Message projections carry available
Run/Step references and use the first public sequence for ordering.

快照携带字符串序号与对应游标。统一投影按 messageId 与 attempt/revision 组装消息，
忽略已应用的序号，缺口要求快照重同步。工具事实按 Thread/Turn/callId 的逻辑调用键合并，
本地调用的参数、状态、进度与 Hands 执行事实共享视图；只有外部执行携带 executionId。
一个 Turn 的不同推理轮次分别投影 assistant 消息，Run/Step 更换不改变逻辑消息 ID。
重新调用模型增加 attempt，重放已提交结果以 revision 替换未完成文本；公开增量来自
middleware 处理后的原生展示事实，与日志原子提交。历史按消息首次公共 sequence 排序。

Executor operations: capabilities; start/query/cancel with request and handle;
output with request and offset. Capabilities declare idempotentStart,
lookupByExecutionId, cancellation and output. NOT_FOUND is a query observation;
persisted uncertain outcomes are UNKNOWN.

`runtime.executionCapabilities()` reports the actual Backend, default static Harness registrations,
Executor capabilities and the names of dynamic `resolvedHarnesses`.
`runtime.capabilities(definition)` returns schema-validated `definitionCapabilities` for
one pinned version, including a dynamically resolved adapter, without claiming work or
running a model. Unavailable definitions/state versions are INCOMPATIBLE. AgentScope declares TOOL_BOUNDARY recovery, DELTA event delivery and
supported content types. These are native journal boundaries, not Java stack replay.
SQLite is durable and local-host; PostgreSQL is durable and shared across hosts.
HttpSessionBackend forwards the server's guarantees. Custom RecordStores must declare
persistence/sharing explicitly. All Backend implementations must preserve atomic
checkpoint+intent, fencing, durable waits and cursor replay.

`runtime.executionCapabilities()` 聚合实际后端、静态默认 Harness 与 Executor 能力，并列出动态解析的
resolvedHarnesses。`runtime.capabilities(definition)` 查询冻结定义的实际能力，覆盖动态解析器；
不会认领 Turn 或调用模型，不兼容定义/状态版本明确拒绝。AgentScope 声明工具边界
恢复、持久增量事件与输入类型，不承诺 Java 调用栈恢复。SQLite 适合本机，PostgreSQL 支持
多宿主共享；HTTP 转发服务端能力。自定义 RecordStore 必须显式声明持久性和共享范围。

SSE: GET `?namespace=...&sessionId=...&cursor=...`; Last-Event-ID takes precedence.
Each committed event uses its cursor as SSE id and `durable` as event name.
A pre-stream expired cursor returns HTTP 410; expiry during streaming emits a
`resync` event then closes. Fetch snapshot, replace the local projection and
reconnect using its cursor. Connections periodically close for bounded resource use.
Heartbeats have no durable cursor. Native sequence, public sequence and output
byte offsets must never be interchanged.

SSE 游标过期时先获取快照并替换本地投影，再用快照游标重连。
连接定期关闭，客户端重连；心跳不推进游标。断开连接不会取消 Turn。
原生序号、公共事件序号、工具输出字节偏移不能混用。

## Queued execution messages

`executionCommand` and `executionEvent` extend `durable.v1` with Broker-neutral
Brain–Hands messages. `ExecutionMessages` validates the JSON schema and canonical
identity/digest. Commands carry EXECUTE, CANCEL or QUERY and the original request.
Events carry a positive safe-integer source sequence and either an output page or an
execution result. Both are immutable; receivers persist out-of-order events but apply
only a contiguous prefix. QUERY requests replay after an acknowledged source sequence.
NOT_FOUND is not an event outcome. Command IDs derive from request digest, kind and
replay position; event IDs derive from request digest and sequence. No FIFO delivery is
assumed. Cancel-before-execute leaves a durable tombstone. QUERY never creates work.
The MQ adapter supports output=true; a terminal outcome follows the output seal. Source
sequences and public SSE cursors are distinct and cannot be forwarded interchangeably.

`executionCommand` / `executionEvent` 定义与 Broker 无关的 Brain–Hands 协议。
身份和摘要包含原请求与重放位置；取消先到留下持久墓碑，查询不创建副作用。
输出与状态是不可变源事件，接收端只应用连续前缀，不允许合并跳序；QUERY 补收缺口。
MQ 支持输出流，终态必须等待输出封口。源序号与公共 SSE 游标不能混用。

## Verified UNKNOWN reconciliation / UNKNOWN 核实

`executionReconciliation` contains requestId, a terminal ExecutionResult and evidenceRef.
It is a host operations contract, not a model tool or an unauthenticated wire operation.
The host verifies the external fact and authorizes the operator, then claims the original
execution with `ExecutionStore.claim(request, owner, lease)` and calls `reconcile(work, command)`.
Only UNKNOWN can transition this way. Audit evidence, outcome and outbox commit together;
a missing output suffix gets an explicit terminal gap before the result. The same request
ID/content is idempotent; changed content conflicts. Query `reconciliation(request, requestId)`
after an uncertain response. Evidence references remain private to the Hands ledger.
Normal result delivery and native result application are unchanged.

`executionReconciliation` 携带稳定请求 ID、已核实终态与证据引用。宿主负责权限和事实核实，
认领原执行后调用 reconcile；只有 UNKNOWN 可由此转入终态。审计回执、结果与 outbox 原子提交，
缺失输出以显式 gap 封口。相同 ID/内容可重试，内容变化冲突，响应不明时先查核实回执。
证据引用只保存在私有 Hands 账本，结果继续经过原有交付与原生应用路径。
