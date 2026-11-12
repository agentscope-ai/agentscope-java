# Managed Agents 内部协议基线

[English](README.en.md)

版本 **0.1.0**。Session Service 已由 Agent Service 同 JVM 承载。通用 HTTP 操作用于多语言执行协调；AgentScope 的原生提交继续使用 Java prepared profile。Hands 保留现有可靠 MQ 编码并明确映射，逻辑消息样例不是 Broker 原始字节。

2026-10-30：Java 也使用共同执行租约；新增 `executions/release`（仅释放匹配 fence，不代表完成）、`prepared/resources` 与可选模型 purpose。`runtime/Notification` 描述 Brain `/api/internal/brain/notify` 的任务提示（202 接纳、429 满载），不属于 Session Service 路由。prepared/query 的 `resolve-input` 由 Service 解析作用域内文件引用。旧协调 profile 仅保留兼容；详见[实施记录](../testing/conformance/verification-java-platform-http-20261030.md)。
2026-10-31：ModelConfiguration 新增可选 `requestTimeoutSeconds`（1–3600 秒），parameters 新增 `topP`（0–1）、`parallelToolCalls`（布尔）、`toolChoice`（auto/none/required）。三套适配器以封存值覆盖原生默认值；省略时保留原有行为。后两个工具控制字段仅适用于 OpenAI 兼容协议，Anthropic 绑定在准入时拒绝。参见[一致性实施记录](../../tests/general-agent-eval/consistency-implementation.md)。


架构与职责见[目录和部署方案](../../docs/design/brain-runtime-layout-and-deployment.md)；设计依据见[协议方案](../../docs/design/brain-session-hands-protocol.md)。本目录是字段、版本、编码和样例的基线，后续修改须同步 Schema、样例与校验。

[实现映射](implementation-map.md)记录实际支持范围、Java prepared 提交配置和待完成的整体联调验收。

## 文件与验证

| 文件 | 用途 |
| --- | --- |
| [openapi.json](openapi.json) | OpenAPI 3.1.1，通用操作及 Java prepared 交接/协调入口；路径前缀 `/internal/session-service/v0` |
| [messages.json](messages.json) | 5 类逻辑 MQ 通道的生产方、消费方、Schema 和 ACK 条件；不是实际 topic 配置或 AsyncAPI 文件 |
| [schemas/common.schema.json](schemas/common.schema.json) | scope、所有权、原生对象、内容引用、错误 |
| [schemas/execution.schema.json](schemas/execution.schema.json) | 认领、续约、可靠延后、Ready/Resume |
| [schemas/commit.schema.json](schemas/commit.schema.json) | 执行提交/回执、输入应用、模型、交互、挂起、终态与公开事实 |
| [schemas/tools.schema.json](schemas/tools.schema.json) | 工具操作、命令、结果、输出和消费回执 |
| [schemas/recovery.schema.json](schemas/recovery.schema.json) | 一致性恢复快照及其读取接口 |
| [preview.schema.json](schemas/preview.schema.json) | 在线文本订阅兴趣与发布；独立于持久化提交 |
| [runtime.schema.json](schemas/runtime.schema.json) | 队列、固定配置、输入和预算接口 |
| [prepared.schema.json](schemas/prepared.schema.json) | Java prepared 提交格式；数值位置限制在 JCS 可精确表示的整数范围 |
| [coordination.schema.json](schemas/coordination.schema.json) | AgentScope 进程、认领、输入、队列、私有 ACK、MCP 目录和子任务关系协调；固定操作白名单 |
| [hands-wire.schema.json](schemas/hands-wire.schema.json) | 实际 Hands v2 封装与解码后的 Java 消息 |
| [examples/index.json](examples/index.json) | 每个正例的文件、JSON Pointer 和 Schema；接口请求/响应与消息通道均有样例 |
| [examples/negative-cases.json](examples/negative-cases.json) | 对正例做局部修改的反例，区分 Schema 拒绝与语义拒绝 |
| [examples/vectors.json](examples/vectors.json) | Java/TypeScript/Python 共用的身份、规范编码与摘要测试向量 |

安装到单独的开发环境后，校验无需网络：

```sh
python3 -m venv /tmp/managed-agents-protocol-venv
/tmp/managed-agents-protocol-venv/bin/python -m pip install -r requirements.txt
make check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
```

以上命令从本目录执行，需要 Python 3.10+ 和 Node.js 18+；Node 校验不安装 npm 包。`make check` 校验 Schema、OpenAPI、本地引用、正反例、交接关联和 Python/Node 的固定向量。`$id` 使用 `.invalid` 标识命名空间，由本地 Registry 解析，不是需要部署的网站。

校验单个消息：

```sh
/tmp/managed-agents-protocol-venv/bin/python scripts/validate.py \
  --instance /absolute/path/message.json \
  --schema 'schemas/commit.schema.json#/$defs/CommitBoundary'
```

**这些是契约校验，不是运行时故障恢复测试。** 不模拟数据库、MQ、外部工具或真实内核；不能据此宣称事务或 ACK 实现已通过验收。

## 接口和服务责任

| operationId | HTTP 路径（均为 POST） | 核心约定 |
| --- | --- | --- |
| `claimExecution` | `/executions/claim` | 对有效 Ready/Resume 意图做可靠认领；同 claimId 重试返回原回执，不创建新 owner |
| `renewExecution` | `/executions/renew` | 只续约尚有效的 owner；同 renewalId 返回原有效期，不能复活过期租约 |
| `deferDelivery` | `/deliveries/defer` | 可靠登记原 Ready/Resume 的延后处理后才可 ACK；不释放已经运行的 owner |
| `loadRecovery` | `/recovery/read` | 一次一致性快照，返回完整 bundle 或 bundleRef；读取不授予写权限 |
| `commitBoundary` | `/commits` | 提交原生恢复位置与平台变化；仅返回 committed，无 202 accepted |
| `getCommitReceipt` | `/commits/receipt` | 按 scope、Turn、commitId 查原回执；404 不代表外部动作没发生 |
| `putNativeObject` | `/objects/write` | 验证字节大小与摘要；scope+objectId 相同内容幂等，不同内容冲突；上传不授权工具 |
| `readNativeObject` | `/objects/read` | 按带 scope 的稳定引用读取并校验对象；不能借引用跨租户或读取任意 URL |
| `getToolState` | `/tools/state` | 返回平台已接受结果或带时间的观察状态；不是外部执行不存在的证明 |
| `previewInterest` | `/previews/interest` | 验证当前 owner 与未结束的 reasoning 调用后查询在线订阅 |
| `previewPublish` | `/previews/publish` | 同一身份校验后尽力发布文本；无持久化、回执查询或重试 |

读取也使用 POST，以便统一携带完整 scope 和结构化对象引用；这些操作仍然只读。服务鉴权使用现有平台服务身份机制，Bearer 仅定义传输位置，不限定 JWT 等 token 格式；服务端必须校验 scope、对象归属和操作权限。executionToken 不是访问凭据。追踪信息放 HTTP/消息传输元数据，不进入业务摘要。

认领和续约的有效期由服务端决定；调用方不得用过期回执开始工作。重试已成功的 claim/renew 不产生新的认领或续约周期，需要新周期时使用新的请求 ID。认领前仍要做容量准入；数据库 fencing、实例 incarnation 和接管资格必须保留，不能仅凭请求中的 owner 信任调用者。

Session Service 初期在 Agent Service 内运行，负责平台提交、工具结果消费和唤醒；Hands 保留独立执行存储与结果 Outbox，自主查询原外部执行。`getToolState` 读取平台观察值，不替代 Hands 的外部查询。本文不新建公共 SSE 或取消 API；用户输入、撤回、取消仍经现有平台 API 受理。

## 数据、身份与摘要规则

- 版本字段精确为 `0.1.0`；不是现有 Java `SessionCommit.schemaVersion=1` 或投影 schema 3。未知字段在协议对象中拒绝，版本不匹配拒绝；不悄悄复用旧版本语义。未来接入需显式映射现有消息，不能直接替换 broker payload。
- scope 固定为 tenant、namespace、sessionId。计数、epoch、版本、字节偏移使用规范十进制字符串，范围为 0..9223372036854775807（正数类型从 1 开始）；不能有前导零。内核游标为不透明字符串，不参与数值排序。
- 协议对象不允许 null 表示删除；可选字段省略代表本次不更新。`Content` 只能选择 `inline` 或 `ref`；其中 inline 为业务/原生 JSON，可包含 null，与协议字段的规则不同。
- 普通元数据请求/响应及解码后的消息体上限 1 MiB UTF-8；大内容改用 ObjectRef。单个对象解码后上限 64 MiB，对象读写 JSON 上限 90 MiB。单个工具输出 chunk 最多 16 KiB。Schema 中另有限制条数；接入方仍需在解析前限制实际传输字节，不能只依赖 Schema。
- 对象引用必须完整、不可变、同 scope，SHA-256 针对原始字节。临时签名 URL 只可作为受控传输手段，不进入持久引用。示例 checkpoint 是合成字节，不是有效 AgentScope/PI checkpoint。

业务对象摘要使用 **SHA-256(RFC 8785 JCS UTF-8)**，输出小写十六进制。CommitReceipt.contentHash 覆盖完整 CommitBoundary，包括 fence、commitId 与 expectedBrainRevision；EventReceipt.contentHash 覆盖完整 ToolEvent。HTTP 头、重试次数等传输信息不计入。数组顺序参与摘要，Unicode 不归一化；拒绝重复 JSON 键、非有限数和非法 Unicode，精确大整数通过字符串表达。规范依据：[RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)。

同 commitId 必须重发原业务体。owner 已变更时先查原回执；成功提交的原回执可读，但不能修改 fence 后用同 ID 重提。需要新的恢复提交时使用新 commitId，原 taskId/executionId 保持不变。

工具身份保留 [ManagedScope](../../common/src/main/java/io/agentcore/managed/server/runtime/managed/ManagedScope.java) 的派生方式：

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

UTF16_length 与 Java String.length、JavaScript string.length 一致，不能用 UTF-8 字节数代替。operationId 由 Adapter 稳定生成并进入原生恢复关联，不能在重启时重新随机生成；一个 nativeCallId 可对应多个 operation，一次批量 operation 也可关联多个 nativeCallIds。示例采用 `call1_0`，不要求所有内核使用这一命名。commitId、claimId、renewalId、deferralId、Ready/Resume messageId 由责任生产方分配并持久化，重复投递不重新分配。messageId 与 scope 联合作去重身份，不使用 traceId。

## 提交、ACK 和恢复约定

提交事务必须校验 scope/Turn、未过期所有权、expectedBrainRevision、原生引用、输入/工具/结果关联及取消等权威状态；重试已提交的相同请求可在旧 fence 校验之前返回原回执，但仍需访问授权。只有 Brain 提交推进 brainRevision；Hands 事件、对象上传、投影或续约不推进它。初始为 0，每次新提交加 1；重复返回原版本。原生状态已持久化并不自动授权工具，平台任务和 Outbox 必须共同提交。

`modelUpdates.dispatch` 表示调用前的身份、恢复点和预算检查/预留，模型请求须等提交成功；complete 携带结果引用和 usage 或 usageUnknown，按原模型调用去重结算，不能把未知用量当成零。交互答复与输入应用必须核对已受理输入和原 action。`inputApplications`、`resultApplications` 与对应原生恢复位置共同提交，不额外添加三份 Applied/Authorized 事件流水。

`modelUpdates` 同时由服务端生成 `model.started` / `model.completed` 公开事实，投影为配对的 `span.model_request_start` / `span.model_request_end`；未知用量省略公开 `model_usage`。`usage.inputTokens` 是包含缓存的总输入，`cacheReadInputTokens` / `cacheWriteInputTokens` 为其子集，不能重复计入；PI 的互斥输入计数由适配层归一化。

Adapter 在原生 assistant 消息进入恢复状态的同一次提交中携带 `publicFacts`，使用 `item.completed` 和稳定的 `item.message_id`；仅投影文本，原始推理、工具参数、压缩摘要不属于公开文本消息。终态可通过 `terminal.messageId` 选择已提交消息，同一身份只公开一次，同时纳入最终输出；未指定时使用 Turn 级兜底身份。身份不可由临时 commitId 派生，也不通过文本相等推断。示例见 [public-projection.json](examples/public-projection.json)。这些是持久化事实；在线 delta 使用下述独立通道。

| 边界 | 允许 ACK / 返回成功的条件 |
| --- | --- |
| 执行提交 | 对象可读取，恢复引用、平台变化、Outbox 和回执已提交 |
| Execute 消费 | Hands 已可靠接单并认领；正常消息路径确认 ACK 后才启动外部执行 |
| 工具事件消费 | 已持久接收/去重；乱序可返回 buffered，不能据此接受最终结果或唤醒 |
| 最终结果 | 已连续接收至该 result 的 sourceSeq，输出覆盖/显式缺口满足；结果与符合条件的 Resume Outbox 同事务 |
| Ready / Resume 消费 | 已可靠接管，或原意图已可靠延后；已验证无效/重复消息可结束，不能仅凭本地 Future ACK |
| 原生结果应用 | 原结果进入可恢复状态后提交 resultApplications；通知或已接收结果不等于内核应用 |

EventReceipt 是消费逻辑的结果描述，不是额外 MQ topic 或 broker ACK 的载荷。outcome=buffered 表示本条已持久缓冲，acceptedResultIds 必须为空；applied 表示连续消费前缀已覆盖本条，可能同时接受此前缓冲的其他结果。throughSourceSeq 是已应用的连续前缀；与 Brain 日志序号分开。

ToolEvent 序号从 1 开始，所有输出、进度、缺口和最终结果共用该工具执行的序号。最终 result 的 output.throughSourceSeq 必须为自身 sourceSeq−1，最终 result 后不再产生新源事件。mode=none 表示没有可恢复输出字节；complete 表示覆盖所有字节；gapped 必须列出同一执行已持久接受的 gapEventIds，并验证 offset 覆盖。结构校验不能代替这些跨消息验证。

RecoveryBundle 是一个固定 snapshotId 下、当前 Turn 恢复所需数据的完整快照，不要求复制整个 Session 历史；大快照使用 bundleRef，解码后仍须满足 RecoveryBundle Schema。不拼接不同快照，不隐式截断必需集合；超出上限返回明确错误。快照及恢复所引用对象必须保留到不再被有效恢复点引用。成功接管会在平台事务中消费对应的等待资格；Bundle 中的 suspension 可保留为恢复锚点，不表示还可以再领取一次同一等待。

恢复按“认领 → 读快照 → 原生恢复 → 核对原任务与结果应用 → 继续/挂起”执行。提交超时查原回执；结果先到则挂起事务复查就绪条件；原生应用回执不明则核对原生记录。外部动作状态不明先查原 executionId，仅明确支持幂等启动的 provider 可按原身份重试。取消是请求；Brain 换 owner 不丢弃原 Hands 的有效迟到结果，也不自动取消它。

## 固定运行配置身份

`executions/configuration` 返回 `ConfigurationResponse`，包含 `protocolVersion`、`scope`、`turnId`、`configuration` 和必填 `identity`。`identity.formatVersion` 为 `managed-effective-agent-v1`，另含 Session 已绑定的 `harnessType`、封存的 `agentId` / `agentVersion`，以及三个小写十六进制 SHA-256 摘要：

- `sourceDigest`：封存 `agentSnapshot` 的 RFC 8785 字节摘要；不冒充控制面已有的 `definitionDigest`。
- `effectiveDigest`：公共合并器应用 Session overrides 后完整定义的 RFC 8785 字节摘要。
- `configurationDigest`：整个响应移除 `identity.configurationDigest` 字段后，按 RFC 8785 编码所得的摘要。因此模型、工具引用、权限、输入、配置身份、scope 和 Turn 都在校验范围内。

Service 按 Session / Turn 固定整个响应，读取缓存时复核 Schema、摘要、scope、Turn 和内核绑定；不因 Worker 重启或部署默认值变化重新编译同一 Turn。PI / Codex worker 在读取恢复状态或创建原生实例前核对该响应。租约 owner / epoch 不进入摘要；Worker 的 inline-wait 默认值仅在校验后应用于本地调度。

本契约校验传输与缓存的一致性，服务认证与资源授权仍是信任边界。它不表示每个定义字段已经映射到全部内核。尚未发布的旧开发缓存缺少 `identity` 时明确拒绝，不在恢复中自动重建；更新 Service / worker 时保留未结执行的原配置与状态。AgentScope 的 prepared 装配路径仍需共同配置语义验收。见[配置身份验证记录](../testing/conformance/verification-configuration-20261029.md)。

### AgentScope 的固定模型选择

`prepared/coordination` 的 `model-configuration` 操作要求 scope、turnId、数值 epoch 和执行 token。Service 在配置解析前后验证当前执行者及进程租约，解析时不持有 Session 事务锁。`result` 为 `ModelBindingResponse`，复用上述 identity 和 JCS 摘要；模型选择与 Session 内核绑定在同一个 Session 锁下首次保存到 `ma.harness_model_configuration`。通用 PI / Codex 配置也复用该模型选择，避免重复解析同一份封存定义。

选择分为两种明确模式：`binding` 携带完整 `model`；`native` 仅用于 AgentScope，在模型目录和 options 都是空对象时保留原生模型名称/默认模型行为。后者封存名称或默认选择，并不封存 ModelRegistry 或部署默认模型内部的 endpoint、参数和凭据。非空目录中的未知别名、非法目录结构或显式绑定缺凭据不会静默降级到 native。

AgentScope 执行工厂先校验 scope、Turn、内核、Agent ID/version 和封存/有效定义摘要，再创建独立模型客户端。目前显式绑定映射 `openai-completions`、`openai-responses` 和 `anthropic-messages`；凭据在 Brain 中按 `MANAGED_MODEL_` 或兼容的 `PI_MODEL_` 环境引用读取，密钥不进入配置。上下文窗口、temperature 和输出上限进入原生调用；请求级参数不能更换模型路由或放宽已绑定上限。Responses 使用官方扩展，映射 `max_output_tokens` 与 `reasoning.effort`，保持 `store=false`。Anthropic reasoning effort 当前没有映射，明确拒绝。

示例见 [prepared-model.json](examples/prepared-model.json)。模型选择只是共同配置的一部分；子 Agent 独立模型、全部定义字段、控制面能力准入及平台定价仍按实施计划验收。

## 在线文本预览

`previews/interest` 和 `previews/publish` 使用服务鉴权及完整 scope、turnId、fence、modelCallId。Service 校验当前租约、模型 dispatch、reasoning purpose，以及尚未提交的 completion；Redis 操作在 Session 事务锁外进行。无订阅、传输失败或拒绝只结束预览，不改变模型/工具执行。

发布请求携带 `messageId`、十进制字符串 `sequence` 和 1—16 个非空文本 chunk（每个最多 1024 Unicode 码点）；请求最多 64 KiB。首次 sequence=0 产生 start(0) 和各 delta(1…N)，后续从 N+1 继续。sequence 计数公开帧，不是 HTTP 批次数。TS/Rust 客户端每批最多 8 个 chunk，使最坏 JSON 转义仍在请求上限内；各有 64 KiB 待发字节、256 个 chunk 的队列上限、250 ms 单请求超时和最多 200 ms 的结束等待。超限或失败停止当前预览且不重试。

消息身份必须与 `item.message_id` / `terminal.messageId` 一致。PI 两个模式把模型调用身份写入 Adapter 自有的 `managed_agents` 消息元数据；旧恢复状态没有该字段时保留原生 entry 身份。PI 只从主回答事件发布；PI Durable 不发布 complete/completeSimple 辅助调用。Codex 根据已声明的 assistant message item ID 与原生 thread ID 生成身份，只消费 `response.output_text.delta`；不识别的身份或不完整前缀不公开。原生工具响应仍须完整校验后才能进入 Core。

服务复用现有 `event_start` / `event_delta` 文本格式、epoch、帧序号和最终事件 reconcile。迟到订阅不从消息中途接入；重复、缺口、旧 epoch、慢消费者可停止该预览。预览不写执行账本、历史或可靠 MQ，不提供 SSE resume / delta 重放；最终持久化消息权威。示例见 [preview.json](examples/preview.json)，定向验证及剩余边界见 [预览验证记录](../testing/conformance/verification-previews-20261029.md)。

## 典型交接样例

| 文件 / 路径 | 阅读顺序与预期 |
| --- | --- |
| [tool-handoff.json](examples/tool-handoff.json) | claim → toolCommit/toolReceipt → execute → suspendCommit → resultEvent/resultReceipt → resume/takeover → recovery → applyResultCommit → finishCommit |
| 同文件 `receiptQuery` | 模拟提交响应丢失：重试原 toolCommit 或查 commit1，返回同一 toolReceipt；不会新增任务或授权 |
| [control-and-output.json](examples/control-and-output.json) | 续约、可靠延后、取消后 unknown；独立 s-output 场景中 final(4) 先到并缓冲，gap(3)、delta(1)、progress(2) 到齐后接受原结果 |
| [inputs-models-and-objects.json](examples/inputs-models-and-objects.json) | 对象读写与摘要；输入/模型预留、完成与交互、答复、拒绝输入、未知用量、多操作关联、引用式恢复快照 |
| [errors.json](examples/errors.json) | 每种协议错误的响应样例 |

不同样例文件/分支是独立测试场景，不是同一个数据库上的连续脚本。示例中的 tokens、配置和对象均为合成值；联调前由真实身份、存储和内核生成。校验器验证交接引用、回执摘要和结构，不模拟执行。

| 错误 code | HTTP | 是否可原请求退避重试 |
| --- | --- | --- |
| invalid_request | 400；传输体过大时 413 | 否 |
| unauthenticated / forbidden | 401 / 403 | 否 |
| not_found | 404 | 否；提交结果不明时按回执查询流程处理，不新建执行 |
| ownership_lost / revision_conflict / identity_conflict | 409 | 否；停止旧执行、重读状态或处理冲突 |
| execution_busy | 409 | 是 |
| budget_exceeded | 409 | 否；等待预算条件或用户操作变化 |
| invalid_reference / unsupported_version / unsupported_capability | 422 | 否 |
| capacity_exceeded / temporarily_unavailable | 429 / 503 | 是 |

仅三种可重试错误提供 retryAfterMs；unknown 工具状态通过 ToolStateResponse 返回，不伪装为可重试 HTTP 错误。

## 后续分工与尚未验证的部分

- **Session Service**：实现 OpenAPI、权威事务校验、对象接口、工具事件消费与可靠唤醒；保留现有权限、预算和持久化保障。
- **Hands**：实现消息映射、持久接单、输出连续性、取消/重放、原执行查询与结果 Outbox；不读取内核 Context。
- **Brain / Adapter**：实现客户端、原生记录与操作关联、持久化屏障、结果应用核对及失去所有权后的停止；先 AgentScope Java，再 PI，Codex 需验证接入能力。

当前已校验的是协议格式和静态关联。真实接入仍必须验证数据库原子性、租约/ACK 竞态、未知外部副作用、取消与迟到结果、模型与预算恢复，以及各内核能否阻断工具并回填原结果。当前实现与新协议的字段映射、这些故障测试完成后，才能宣称运行能力就绪；不能把 Schema 通过等同于可靠执行通过。


## PI 恢复与交付扩展（2026-10-28）

`recovery/read` 可选 `pageSize=1..16`，后续请求携带上页 `nextCursor`（afterRevision/throughRevision）作为 `cursor`。Service 固定 native head 的版本；版本变化或过期 fence 拒绝。超过 512 KiB 的页使用 `bundleRef`。未传 pageSize 的旧调用保持原行为。结果/输入观察可在分页期间推进，不能据此跳过工具账本对账。

`deliveries/defer` 新增可选 fence；已有 owner 时必须匹配，未携带 fence 的请求只能延后无人持有租约的交付。延后影响 HTTP polling 和 resume outbox，不释放租约。同 deferralId 保持原回执。

固定配置的 model 支持受校验的 endpoint/API/凭证环境变量名/token 限额/价格/采样参数；禁止 credential 正文。详细操作和格式升级限制见 [PI 部署说明](../images/pi/README.md)。这些可选扩展要求先升级 Service；旧客户端请求无需变更。

通用配置另支持 model 别名目录和 `MANAGED_MODEL_` 凭据引用（兼容 `PI_MODEL_`）。非空目录拒绝未知别名，选中的完整绑定随 Turn 配置封存；不要从其他 provider 继承默认 options。服务与 Adapter 校验输出/上下文限额和 reasoning 一致性，Codex 仅接受 Responses API。该能力目前用于 PI / PI Durable / Codex 通用路径，AgentScope prepared 路径和按绑定定价仍待对齐。字段映射与部署变量见 [Codex 模型绑定](../rust/README.md)，验证见[模型绑定记录](../testing/conformance/verification-model-bindings-20261029.md)。
