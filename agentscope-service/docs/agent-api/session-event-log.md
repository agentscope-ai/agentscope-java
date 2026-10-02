# Session Event Log 与 Agent API v1

本文为维护者的实现与存储参考。业务应用接入请从 [Agent API 使用指南](../../../docs/v2/zh/service/session-event-log.md) 和 [SSE 事件与前端接入](../../../docs/v2/zh/service/sse-events.md) 开始；[English Agent API](../../../docs/v2/en/service/session-event-log.md) / [SSE guide](../../../docs/v2/en/service/sse-events.md)。实施范围和验证见[补齐记录](../../../internal/implementation/agent-api-completion-20261001.md)。


实现位置：`agentscope-core/session`、`agentscope-harness/agent/session`、Service Data Plane 和 Console 的 Execution 页签。

## 用户接入指南

本文保留实现和协议参考。面向应用开发者的完整步骤见：

- [HarnessAgent：存储位置、原始事件、导出与 checkpoint 恢复](../../../docs/v2/zh/docs/harness/session-log.md)。
- [Managed Agent：创建 session、提交 turn、SSE 与前端、actions/cancel/resume](../../../docs/v2/zh/service/session-event-log.md)。
- [已有 SSE 接口：Agent API、Endpoint 与旧 Managed Session 的区别](../../../docs/v2/zh/service/sse-events.md)。
- [English Agent API guide](../../../docs/v2/en/service/session-event-log.md)。

## 分层与权威来源

- **Native Session Log** 保存执行事实，是 event-log 模式下恢复状态的依据。模型请求、adapter chunk、工具分派和返回、完整消息、交互、上下文替换、任务/计划/权限/验证状态均在这里。输入对象在接收时序列化，之后修改 Java 对象不会改变历史。
- **AgentState / transcript** 是带水位的派生状态。compaction 追加替换事实和 checkpoint，历史消息不会删除。旧 JSONL 存档保留，但不继续双写；兼容展示由 SessionTranscriptExport 按需生成。
- **Public Event Log** 是 Service 数据库里的公共投影。平台拥有 command、取消、artifact 发布等事实；Harness 拥有执行事实。native eventId 去重，独立公共 seq 生成 opaque cursor。普通 SSE 不暴露原始 request、checkpoint 或 thinking；业务工具参数、进度与输出经公共块过滤后可读。
- **Aistio** 从 committed native facts 导出，用持久 source watermark 去重；传统 AgentEvent 仍承担低延迟交互和旧协议适配。

这一分层参考了 DeepSeek Harness 的 append-only 执行追踪、工作上下文与完整历史分离、请求记录和恢复边界；公共 session/turn/item/required action 与持久事实/展示状态的分层参考 OpenAI Agents API 与 Claude Managed Agents。它是 AgentScope 自己的版本化协议，不宣称第三方 wire compatibility。

## 执行身份与逻辑 turn 状态

一次提交对应一个 `turn_id`，挂起后 actions/resume 仍使用它。每次后台实际执行有独立 `run_id`，与 SDK AgentRun.runId 和原生 executionRunId 相同。来自原生执行的公共事件在 `data` 中携带这两个 ID；`item.delta` 增量同样携带它们，并使用持久游标补放。排队、取消等尚未执行的命令事件可以没有 run_id。

例如，同一个 turn 先经历 `run.started(r1)` → `run.ended(r1, suspended)` → `turn.requires_action`，答复后再经历 `run.started(r2)` → `run.ended(r2, completed)` → `turn.completed`。以 command inbox 发布的 `turn.*` 状态判断整轮结果；不要把 run.ended、旧 turn.ended 或 SDK handle 的 COMPLETED 当作逻辑成功。原生 turn 生命周期用于内部追踪，不重复投影成公共 turn 终态。

snapshot 的 `turns` 只保存逻辑命令状态，`runs` 保存各 run 的最新执行边界事件。旧 native turn.started/turn.ended 不会覆盖逻辑状态。usage.recorded 使用 `model_call_id` 去重，usage.model_calls 表示模型调用数，不表示调度重试次数；读取历史 attempt_id 用量记录仍可去重。

Control Plane 的 ManagedExecutionScope.turnId 是编排准入 ID，attemptId 是调度执行尝试，均不替代上述 ID。内部 `session.run_started` 持久保存 run_id、turn_id 和 `coordination.{control_plane_turn_id, orchestration_attempt_id, agent_task_id, dispatch_generation}` 关联；原控制面 fence 协议保持不变。旧 Managed Session 工具结果续跑入口也会从原生日志中的 pending request 找回原 turnId，拒绝未知或跨 turn 的结果。

## 身份与事件目录

逻辑 SessionKey = userId + stable agentId + sessionId。turnId 表示可多次恢复的逻辑 turn；executionRunId 表示一次实际执行。模型 modelCallId、toolCallId、actionId、父子 session 引用分别保留。显示名称和进程内 Agent UUID 不作为托管日志目录的替代键。

Native envelope v1：`schemaVersion, eventId, seq, occurredAt, type, executionRunId, turnId, required, payloadJson`。`payloadJson` 是已冻结的 JSON 字符串，trace 读取者可解析；时间戳不用于排序。未知 required 类型或不支持的版本会阻止恢复。应用可通过 `SessionEventCodecRegistry` 注册非保留命名空间的校验器和状态 reducer。

| 事件族 | 记录内容 |
| --- | --- |
| run / turn / step | 开始、结束、停止请求、执行状态与结束原因，以及 post-call middleware 后的最终输出 |
| input / message | 收到的输入、实际进入上下文的输入、完整消息；重试通过稳定 message ID 去重 |
| request / model | middleware 后的最终 adapter 请求、实际分派、chunk、usage、结束、fallback/retry |
| action / tool | 请求、决策、action 起止、真实分派、输出 chunk、结果；分派前必须确认日志提交 |
| interaction | 按 toolCallId 关联的 confirmation / external_execution 请求与已应用结果 |
| context / compaction | context 构造事实、压缩起止、替换后的完整工作上下文；原历史保留 |
| task / plan / permission / verification | 状态变化、验证证据及判定；不能把持久化失败当成验证通过 |
| subagent | 父侧创建/可观察完成，子日志里的父 session/run/turn 关联；子运行有独立身份与 writer |
| migration / recovery / checkpoint | 来源可解释的 baseline、显式工具结果核对、恢复修复、完整 state checkpoint |
| presentation / extension | 展示提示、显式扩展事件；不重复记录已有底层工具事实 |

只记录适配器实际暴露的 chunk，header 的 `replayCoverage=adapter_chunks` 不代表 provider wire capture。失败或取消时保留已有 chunk，但不把未完成输出伪造成完整消息。

## Workspace 存储与扩展

Harness 默认 `WorkspaceSessionLogStore`，使用 Workspace 的 Filesystem 能力；并不假定 workspace 是本地磁盘。可用 `builder.sessionLogStore(customStore)` 替换。

逻辑布局：

```text
agents/s_<base64url(agent)>/sessions/s_<base64url(user)>/s_<base64url(session)>/
  session.json                  # 格式、权威模式和覆盖标记
  head.json                     # seq、writer epoch/lease、可见 commit 指针
  commits/<batch-id-hash>.json   # 不可变批次，parent/hash/事件/载荷引用
  blobs/<sha256>                # >64 KiB 的不可变载荷
  exports/<sink-name-hash>.json # 各导出器已确认水位
```

LocalFilesystem 将它放到所解析 workspace 的 `.agentscope-runtime` 下。**本地文件带 8 字节版本前缀，不是可直接逐行解析的 JSONL 文件**；请用 SessionLog API 读取。RemoteFilesystem 复用已有 BaseStore 和 namespace resolver，在私有 `__agentscope_session_log_v1__` 分区存储。Overlay 使用 upper；Composite 由 `agents/` 路由选择后台。若需要其他路由，注入自定义 SessionLogStore。

Service 默认使用现有共享 BaseStore 的 `runtime/sessions` 命名空间，日志不依赖一次性执行 sandbox 的寿命。可以提供替代 `SessionLogStore` Spring Bean。

| Backend | 支持条件 |
| --- | --- |
| Local | OS 文件锁、atomic rename、文件和目录 fsync；不能把临时盘当作长期存储 |
| InMemory | 支持原子版本操作；仅进程内、不可跨重启恢复 |
| Redis / JDBC / MySQL / PostgreSQL / Mongo / ControlPlane | 使用已有原子 CAS 能力，并显式声明 `supportsAtomicSessionStorage` |
| OSS / COS | 当前 BaseStore 的 read-check-write 不能作为原子 CAS；不会自动降级使用 |
| Sandbox / 自定义 Filesystem | 实现 `sessionStorage`，或显式提供外部日志 store，或使用 legacy 模式 |

`AtomicSessionStorage.compareAndSet` 的 ACK 必须表示后端确认该版本写入，expectedVersion=0 表示不存在时创建。存储操作在 boundedElastic 上执行。writer epoch 与租约 fencing 防止过期 writer 提交；不可变批次写完后，只有成功 CAS head 的前缀才可见。未引用 blob/commit 不参与恢复。后台导出不阻塞模型/工具的原生提交确认；失败保留 cursor 供重试。

读历史使用 stable-prefix scan，一次遍历 commit 索引并逐批读取载荷；不为每一页重复水合完整 blob 历史。当前启动恢复仍需验证历史链并读取 checkpoint 前缀，没有自动 GC、分层归档或无限规模性能承诺。

Filesystem 文件工具拒绝访问保留目录；这不是对持有宿主 shell/root 权限的调用者建立安全沙箱。后端自身的落盘、复制与保留策略仍决定物理耐久性。

## 模式、迁移与恢复

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.session.SessionHistoryMode;
import io.agentscope.harness.agent.HarnessAgent;

HarnessAgent agent = HarnessAgent.builder()
    .agentId("research-assistant") // 稳定 ID
    // ...模型、workspace 和工具配置...
    .sessionHistoryMode(SessionHistoryMode.EVENT_LOG)
    .build();
RuntimeContext rc = RuntimeContext.builder().userId("alice").sessionId("s1").build();
agent.call(UserMessage.builder().textContent("开始研究").build(), rc).block();
var history = agent.sessionTranscript(rc); // 完整历史及 asOfSeq
var inspection = agent.inspectSession(rc); // 只读；不会自动调用模型或工具
```

纯 Core 可通过 `ReActAgent.builder().sessionLogStore(...)` 显式启用；默认不会偷偷创建目录。

- `LEGACY`：旧 AgentStateStore；`legacySessionHistory(true)` 为兼容入口。
- `EVENT_LOG`：仅从原生日志恢复，不创建、读取或双写旧 state store。提交失败立即阻止后续副作用，不回落到 legacy。

SHADOW 双写模式已删除；旧 shadow header 会明确拒绝加载。切回旧二进制前必须暂停、核对并导出状态，使用新的 legacy session；不要用 legacy 开关覆盖一个已有 event-log session。

工具取消只记录停止请求和可确认的状态，不能保证已发出的外部副作用已被撤销。暂停在确认/外部输入时，取消会关闭该交互并以 interrupted 结果留痕。

显式导入已有完整 AgentState 会记录 `migration/baseline`，标注 `baseline_only`，不声称拥有过去的模型请求和 chunk。需要显式离线导入、保留来源版本/hash或 fork 时，使用 `SessionMigration.importBaseline/exportState/fork`。旧 JSONL 档案保留；截断 transcript 不作为完整状态导入源。Fork 是已核对状态的基线复制，不是重放副作用或无损复制外部执行资源。

恢复发现已分派而无确定结果的工具，会拒绝继续。由调用者核对真实外部结果后，调用 `SessionRecovery.reconcile` / `HarnessAgent.reconcileToolOutcomes`；必须覆盖准确的未决 toolCallId 并提供原因。记录核对结果、恢复事实与 checkpoint 后才能显式 resume。不能通过“再执行一次试试”代替结果核对。

父子 Agent 各自有日志、turn 和 writer。内置子 Agent 工厂继承父配置的日志后端与模式；经父 session 授权的 `GET /{session}/trace/subagents/{child}` 可读取直接关联的子日志。父日志不会继续使用已释放的 writer 写入异步子任务完成；脱离父调用的子任务，以子日志和既有 TaskRepository 的结果为准。

## Agent API v1

完整路由和逐场景示例以中英文用户指南为准。机器契约为 [OpenAPI](openapi-v1.json) 与 [公共事件 schema](public-event-v1.schema.json)。包括 lifecycle、turn/actions/cancel/resume、steer/inject、结构化输入、公共子会话、files/artifacts、checkpoints/restore/fork/export、usage/budget、webhooks 和 trace。

- 公共模型片段使用 `item_model_<modelCallId>`，最终消息通过 response/message 身份关联回同一 item。只展示 REASONING 用途的公开内容，不暴露辅助 compaction 请求或 thinking。
- `AgentSessionView` 和前端 `agentSessionView.ts` 折叠部分消息、工具参数与进度。完整 item 替换增量；工具卡独立于消息，以 turn/call 标识归并。
- `SessionEventLog.page` 在数据库固定 through 水位下分页。`AgentSessionViewStore` 将派生状态以 CAS 保存到 BaseStore，之后只折叠后缀；初次构建仍读取全部历史。资源分页缓存 15 分钟，cursor 与事件 cursor 不混用。
- 原生 inbox 接收 steer/inject；SessionExecution 在步骤边界消费，并以同一个 inbox writer 串行化终态关闭与 steering 接收。取消未完成 turn 也关闭尚未应用的 steering。
- 命令状态与 turn.running/queued、action 投递拒绝事件事务提交。答复 accepted 与原生 resolved 分开；拒绝事件带 request_id/pending，视图恢复仍未解决的待办。
- 子来源登记 parent_session_id，以本地公共导出代替对子会话使用控制面根 session 镜像。公共读取验证原生父子关联。子树用量返回各自水位，不假装全树原子快照。
- SessionModelPolicy 在模型调用前 admission、model/end 提交后核算；子上下文继承同一预算策略。CAS ledger 预留调用次数，未报告 token/费用不视为零；原生日志用于修复崩溃后的预留记录。
- SessionCheckpointService 在原生 writer 租约内选择、核对并提交基线/恢复事实；不重写既有历史。恢复要求已结清命令和工具副作用，fork 目标为空且使用同一个 Agent。

## 存储与部署

服务新增资源使用可替换、共享且支持原子 CAS 的 BaseStore：`runtime/agent-api/files`、`views`、`resource-pages`、`budgets`、`webhooks`、`webhook-deliveries`。这些 namespace 不依赖 worker 临时目录。文件内容不可变并带 SHA-256，普通下载验证 owner；内部 blob 永不自动公开。

JPA 新增/扩展表为 builder_session_turn_command、builder_session_action_command、builder_session_export_source；公共事件沿用 builder_session_event。使用 ddl-auto=validate 或手动迁移的 PostgreSQL 部署，先审核并运行 [DDL](../../service-dataplane/src/main/resources/db/manual/agent-api-v1-postgresql.sql)。该文件不由应用自动执行。已存在的公共事件表应具备 `(session_id, seq)` 唯一索引及 event_id 唯一约束。开发环境 ddl-auto=update 会维护实体结构。

```yaml
builder:
  agent-api:
    inbox-poll-ms: 1000
    export-poll-ms: 5000
    trace-enabled: false
    files:
      max-bytes: 16777216
    webhooks:
      allowed-hosts: "notify.example.com"
      poll-ms: 2000
    pricing:
      currency: USD
      # 部署方维护价格；这里是格式示例，不是模型报价。
      models: '{"your-model":{"input_per_million":1,"output_per_million":2}}'
```

Webhook 默认无允许主机，因此不接受外发目的地；不跟随 HTTP 重定向。注册签名密钥仅在创建/同键重试返回；日志和列表不返回。共享目录记录租约、当前事件水位、重试次数与投递结果。接收方验签并按事件 ID 幂等处理；投递语义是至少一次。

公网 event export 是公共 JSONL，不是包含凭据或全部 prompt 的 native journal 导出。trace 默认关闭且另需 ROLE_ADMIN/ROLE_SESSION_TRACE。Checkpoint rollback 与 fork 都不复制或撤销外部环境和工具副作用。

已有公共事件保持不可变，旧历史里没有导出的部分片段不会因为升级自动补造；新执行写入可补放片段，旧完整 item 仍可读取。`preview` 参数保留为无效果的轻量入口，当前执行不再生成独立的进程内预览。

## 验证与后续回归

针对性验证及结果记录在 [实施记录](../../../internal/implementation/agent-api-completion-20261001.md)。多副本故障注入、真实 Webhook 网络投递、各持久后端、长历史容量与真实模型/文件组合需要集中回归，见 [回归清单](../../../internal/implementation/session-event-log-regression-backlog.md)。

参考：[OpenAI Agents API events](https://developers.openai.com/api/docs/guides/agents-api/sessions/events)、[Claude Managed Agents events](https://platform.claude.com/docs/en/managed-agents/events-and-streaming)。
