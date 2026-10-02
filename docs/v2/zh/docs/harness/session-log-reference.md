---
title: 会话日志 API 与存储参考
description: 查询原生事件、配置后端、导出历史、扩展事件，以及迁移和 fork 会话。
en_link: /v2/en/docs/harness/session-log-reference
---

本文供需要查询完整执行记录、配置日志后端或开发自定义集成时查阅。常见用法见[会话操作指南](/v2/zh/docs/harness/session-log)。

无论应用使用 `call` / `streamEvents`，还是 `AgentSession` 调度任务，HarnessAgent 默认都通过 Session Log 保存执行记录。读取日志不要求启用会话调度，也不会重新执行 Agent：

```java
var log = agent.sessionLog(ctx); // ctx 与执行时使用相同的会话身份。
var events = log.readAfter(0, 100);
```

## 事件结构与读取

SessionEvent 是不可变的原生记录，载荷在接收时冻结为 JSON。

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | envelope 版本 |
| `eventId` | 单条原生事件 ID，导出端可用来去重 |
| `seq` | 当前 session 内的持久顺序，从 1 递增 |
| `occurredAt` | 事件时间；排序使用 seq |
| `type` | 事件类型 |
| `turnId` / `executionRunId` | 逻辑请求与执行身份；管理操作可能没有 turnId |
| `required` | 恢复时是否必须识别该类型 |
| `payloadJson` / `data()` | 冻结的 JSON / 解析后的 Map |

`readAfter(seq, limit)` 返回该 seq 之后的已提交事件。`scan(after, through)` 读取固定前缀，through 应取自 `log.head().seq()`。读取历史不会调用模型或执行工具。

### 事件目录

| 类型 | 主要内容 |
| --- | --- |
| `run/start`、`run/end`、`run/stop_requested` | 一次执行的开始、结果和停止请求 |
| `turn/start`、`turn/resumed`、`turn/output` | 逻辑请求首次启动、恢复及最终输出 |
| `turn/completed`、`turn/suspended`、`turn/failed`、`turn/interrupted`、`turn/cancelled` | 逻辑请求的结果或暂停状态 |
| `step/start`、`step/end` | 推理步骤 |
| `input/received`、`input/applied`、`input/discarded` | 输入接收、进入上下文或丢弃 |
| `message/system`、`message/user`、`message/assistant` | 消息历史 |
| `request/prepared`、`model/dispatch`、`model/chunk`、`model/end`、`model/retry` | 最终适配器请求、模型分派、返回片段、用量和重试 |
| `tool/requested`、`tool/decision`、`tool/dispatch`、`tool/chunk`、`tool/result` | 工具请求、权限决策、分派和结果 |
| `action/start`、`action/end` | 具体工具动作的执行边界 |
| `interaction/requested`、`interaction/resolved` | 待答交互及应用的结果 |
| `context/build`、`context/replaced`、`compaction/start`、`compaction/end` | 上下文构造、替换和压缩 |
| `task/changed`、`plan/changed`、`permission/changed`、`verification/result` | 任务、计划、权限和验证状态 |
| `subagent/spawned`、`subagent/completed`、`subagent/linked` | 父子会话关联；子 Agent 的完整记录在子日志中 |
| `state/checkpoint`、`recovery/applied`、`migration/baseline` | 恢复状态、核对结果和导入基线 |
| `presentation/hint`、应用扩展类型 | 展示提示和自定义事实 |

记录范围是适配器可见的请求和输出，不包括 provider 内部过程或引用文件的自动备份。原始请求、工具结果和 checkpoint 适合受控诊断入口；浏览器公共事件应经过内容筛选。

## 存储位置与后端配置

Harness 默认使用 WorkspaceSessionLogStore，跟随 Workspace 的 Filesystem 路由。本地记录位于当前身份解析后的 Filesystem 根目录下的 `.agentscope-runtime/`；RemoteFilesystem 使用对应 BaseStore namespace 的 `__agentscope_session_log_v1__` 分区。

一个逻辑会话由 `SessionKey(userId, agentId, sessionId)` 定位。其存储目录形如：

```text
agents/s_<agent>/sessions/s_<user>/s_<session>/
  session.json
  head.json
  commits/<batch-hash>.json
  blobs/<sha256>
  exports/<sink-hash>.json
  inbox/                       # durable command journal
    session.json
    head.json
    commits/<batch-hash>.json
```

身份段使用 Base64URL 编码。commits 保存提交批次，blobs 保存较大的载荷，exports 保存各导出目标的确认水位。这些是存储对象，不是供应用直接逐行解析的 JSONL；本地对象还包含版本前缀。请通过 SessionLog API 读写。

会话操作还使用同一后端下的 `inbox/` 接收任务、引导、注入及回复。收件箱有独立的提交序号和短期 writer，不与执行日志的 seq 或公共 SSE cursor 混用。`inbox/accepted` 保存接收事实，`inbox/opened` / `inbox/closed` 记录执行接收边界，`inbox/handled` / `inbox/rejected` 记录控制处理和拒绝；执行日志中的 `inbox/started` / `inbox/applied` 关联实际执行与输入应用。消费不会删除原接收记录。

同一个 Agent 会复用相同身份的 `AgentSession`，使用首次创建时的 RuntimeContext 副本调度任务。请使用稳定的会话级配置，并保持用户身份与存储 namespace 一致。

自定义 `SessionLog` 后端需实现 `inbox()` 才能使用 AgentSession 命令；默认 JournalSessionLog（含 Workspace 和内存后端）已支持。输入仅在 checkpoint 提交后标记为已应用。

### 将日志存到共享后端

工作文件与日志可以使用不同后端。下面的 sharedStore 是应用已配置、支持原子版本写的 BaseStore：

```java
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.util.List;

var logStore = new WorkspaceSessionLogStore(
        new RemoteFilesystem(sharedStore, List.of("my-app", "session-history")));
// 传给 HarnessAgent.builder().sessionLogStore(logStore)。
```

Local、Redis、JDBC、MySQL、PostgreSQL、Mongo 和 ControlPlane 后端具备对应的原子存储实现。InMemory 适合进程内演示，重启后不保留。自定义 Filesystem 应提供 sessionStorage 能力，或单独配置 SessionLogStore；普通文件读写接口不足以保证日志提交的原子性。

配置多副本时，保持 SessionKey 与 namespace 一致，并使用共享后端。列表发现受当前 namespace 范围约束；SESSION 隔离的列表不用于枚举其他 session。

### 自定义后端与备份

可以实现 SessionLogStore，或实现 AtomicSessionStorage 并复用 JournalSessionLog。后端需要支持原子版本比较写入、writer 租约、过期 writer 隔离、按序幂等提交及导出水位持久化。SessionLogStore.list(RuntimeContext) 用于会话发现。

备份应覆盖 header、head、可达 commits/blobs 和 exports，并使用后端一致快照或暂停 writer。工作文件、外部产物和工具依赖需要各自备份。保留与归档策略由部署方管理。

## 导出到其他系统

SessionExportSink 负责把已提交事件交给目标系统。accept 返回应表示目标已经持久接收；同一 eventId 可能重复交付，目标必须幂等。

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionExportSink;
import io.agentscope.core.session.SessionLogExporter;

// sink 是应用实现的 SessionExportSink。
RuntimeContext exporting = RuntimeContext.builder(rc)
        .put(SessionExportSink.CONTEXT_KEY, sink).build();
// 用 exporting 执行 Agent。应用启动或空闲时，还可以补投已提交记录：
new SessionLogExporter(agent.sessionLog(exporting), sink).drain();
```

sink.name() 应跨部署保持稳定，它标识持久导出水位。drain 只投递历史，不重新执行 Agent。应用需要安排补投，并协调多副本对同一个 session/sink 的导出。托管 Service 已提供公共事件导出和 SSE 接口。

## 记录应用自己的事件

在运行时传给中间件或工具的 RuntimeContext 中取得 SessionRecorder，追加应用命名空间的事件：

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionRecorder;
import java.util.Map;
import reactor.core.publisher.Mono;

static Mono<Void> recordReview(RuntimeContext context, String note) {
    SessionRecorder recorder = SessionRecorder.from(context);
    if (recorder == null) return Mono.empty();
    recorder.append("acme/review_note", Map.of("note", note), false);
    return recorder.flush();
}
```

将返回的 Mono 组合进调用链，才能等待提交。required=false 适合不参与状态恢复的诊断信息。需要参与恢复的类型，应通过 SessionEventCodecRegistry 注册校验器和 reducer，并在执行或恢复之前完成注册。未知的 required 类型会阻止恢复。调用结束后不要继续持有 recorder 写入。

## 核对结果未知的工具

工具已产生外部效果而结果尚未提交时，恢复会列出 uncertainToolCalls。先核对实际结果，再提供恰好覆盖这些 ID 的结果集合：

```java
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;

var result = ToolResultBlock.builder()
        .id("call-id-from-inspection").name("create_order")
        .output(TextBlock.builder().text("已核实订单 ORD-42 创建成功").build())
        .state(ToolResultState.SUCCESS).build();
agent.reconcileToolOutcomes(rc, Map.of(result.getId(), result), "已核对订单系统记录");
```

该操作保存核对结果和 checkpoint，不再次执行工具。随后显式恢复原 turn。结果仍未知时，不要构造空的成功结果。

## 迁移与分支会话

| API | 用法 |
| --- | --- |
| `SessionMigration.importBaseline(target, state, source, sourceVersion)` | 向空目标导入完整 AgentState，保留来源信息 |
| `SessionMigration.exportSnapshot(source)` | 导出状态及 asOfSeq |
| `SessionMigration.exportState(source)` | 只导出状态 |
| `SessionMigration.fork(source, destination, destinationKey, sourceReference)` | 在新身份下从当前状态建立会话 |

导出和 fork 前应停止源执行并解决未知工具、未闭合 run 和待答交互。导入的 baseline 提供当时的状态，不补造以前的执行历史；fork 不复制原会话的历史、sandbox 或外部资源。
