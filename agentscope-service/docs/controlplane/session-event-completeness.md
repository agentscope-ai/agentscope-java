# 会话事件记录完整性

> 状态：**工具 I/O 落库与 preview event_id 对齐已落地**（见 [managed-agents-followups.md](./managed-agents-followups.md) §1）；热尾保留策略仍待实施
> 问题域：**记录什么**——落库事件的粒度与内容完整性
> 关联文档：[session-transcript-append.md](./session-transcript-append.md)（怎么写）、[session-storage-topology.md](./session-storage-topology.md)（存在哪）、[sdk-design.md](./sdk-design.md)

---

## 1. 现状：落库的工具事件是空壳

### 1.1 已确认的行为

`SessionEventMapper` 把 harness 的 `AgentEvent` 映射为「持久化事件」与「仅流式预览帧」两类，类注释明确声明：

```
Streaming deltas are never persisted.
```

三类 delta（`TextBlockDeltaEvent` / `ThinkingBlockDeltaEvent` / `ToolCallDeltaEvent`）走 `previewOnly`，进内存的 `SessionEventPreviewBus` 再 SSE 推给前端，不落库。这部分是刻意设计，本身没问题。

问题在**持久化的那一半**：

| 事件 | 落库内容 | 缺什么 |
|------|---------|--------|
| `AgentResultEvent` → `agent.message` | 完整文本 | 完整 |
| `ToolCallStartEvent` → `agent.tool_use` | id / name，`input` 写死为 `Map.of()` | **工具参数** |
| `ToolResultEndEvent` → `agent.tool_result` | id / name / state | **工具结果正文** |
| `ModelCallStartEvent` / `ModelCallEndEvent` → span | usage | 完整 |

`SessionEventMapper` 中的两处：

```java
// ToolCallStartEvent 分支
payload.put("input", Map.of());          // 恒为空

// ToolResultEndEvent 分支：只有 tool_use_id / id / name / toolCallId / toolName / state
// 没有任何 output 字段
```

### 1.2 消费端与 schema 都预留了位置

这不是「设计上不需要」，而是生产端漏了：

- `AgentScopeContractController` 读 `payload.get("input")` 与 `payload.get("output")`（fallback 到 `text`），映射成 `toolInput` / `toolOutput`。
- aistio runtime store 的 `session_events` 表有 `tool_input JSONB` 与 `tool_output TEXT` 两列。

只有客户端回传的 `user.tool_result` / `user.custom_tool_result` 这条路带内容（`DataSessionApiController`、`SelfHostedWorkerController`），harness 侧自执行的工具全部丢失。

### 1.3 用户可见的后果

**刷新页面后，历史里的工具调用是「有名字、无参数、无结果」。** 当前事件日志不是一份可回放的完整记录；审计、复盘、问题定位都无法基于它进行。

同时这也解释了「为什么数据量看起来不大」——量小是靠丢内容换来的，不是天然如此。

---

## 2. 修复路径：数据都在，只是没接

好消息是**不需要改 `agentscope-core` 的事件 API**。缺的两块都能从现有事件累积得到。

### 2.1 事件对象的实际字段（已核对）

| 事件类 | 字段 |
|--------|------|
| `ToolCallStartEvent` | `replyId` / `toolCallId` / `toolCallName`——**无 input** |
| `ToolCallDeltaEvent` | 同上 + `delta`（工具参数 JSON 的片段） |
| `ToolCallEndEvent` | 存在，**`SessionEventMapper` 未处理** |
| `ToolResultStartEvent` | 存在，**未处理** |
| `ToolResultTextDeltaEvent` | 存在，**未处理**（结果正文片段） |
| `ToolResultDataDeltaEvent` | 存在，**未处理** |
| `ToolResultEndEvent` | `replyId` / `toolCallId` / `toolCallName` / `state`——**无正文** |

`SessionEventMapper.map()` 目前对上面标「未处理」的四个事件一律返回 `MappingResult.empty()`，这正是两个缺口的直接成因。

### 2.2 实施要点

1. **`SessionEventMapper` 改为有状态**：按 `toolCallId` 维护累积缓冲。现有的 `PreviewIds` 已经是按 `toolCallId` 分组的每轮状态容器，可在此基础上扩展，或提取独立的累积器。
2. **工具参数**：累积 `ToolCallDeltaEvent.delta`，在 `ToolCallEndEvent` 时组装成完整 JSON 并落 `agent.tool_use`。注意这会把该事件的落库时机从 Start 推迟到 End。
3. **工具结果**：累积 `ToolResultTextDeltaEvent` / `ToolResultDataDeltaEvent`，在 `ToolResultEndEvent` 时落 `agent.tool_result` 并带完整 output。
4. **异常路径**：turn 中断、工具超时、`AllToolsDeniedEvent` 等情况下缓冲需要落盘或显式标记为不完整，不能静默丢弃。
5. **大 payload 外置**：单条工具输出超阈值（建议 64KB）时，事件行只留引用 + 大小 + head/tail 预览，正文单独存对象。否则编码类 agent 的文件读取会直接把事件行撑到 MB 级。详见 [session-transcript-append.md](./session-transcript-append.md)。

### 2.3 粒度决策（已定）

记录**完整语义事件**：完整消息 + 工具完整 input/output + 模型 span 与 usage。**不记录逐 token 的 delta。**

理由：语义事件足以支撑内容回放、审计与问题定位；逐 token delta 只多提供「打字机效果的时序保真」，但会让单会话行数增加约 10-50 倍。

> 后续已进一步决策放弃 SSE 推送、改为客户端自适应轮询（见 [session-readpath-cost.md §3.2](./session-readpath-cost.md)），因此逐 token 打字机效果不再由任何通道提供。注意这不是本次的新损失——`ChatPanel` 从不传 `event_deltas`，当前 Build 侧本就收不到流式帧，回复是一次性出现的。

---

## 3. 事件日志缺保留策略

`builder_session_event` 目前**只有 `deleteBySessionId`**，在会话删除时调用。没有任何按时间的保留或归档机制——会话不删，事件永久累积。

对比：aistio runtime store 侧的 `session_events` 有 `RetentionWorker`，默认 7 天（`DefaultRetention()`）。两侧生命周期策略不一致。

补齐 tool input/output 之后单行体积会显著变大，这个缺口的紧迫性同步上升。

**已决策的解法**：`builder_session_event` 降级为**短保留的热尾表**（建议 24 小时量级，具体窗口待定），冷体由对象存储上的 JSONL 分段承载。这样保留策略与热尾/冷体分层是同一件事，不需要单独设计。详见 [session-readpath-cost.md §3.3](./session-readpath-cost.md)。

实施要点：

1. 为 `builder_session_event` 增加按时间的清理任务，窗口与热尾保留策略一致。
2. 明确热尾与冷体的切换点：读取区间落在保留窗口内走关系库，否则走对象存储；两者 seq 空间必须连续且不重叠。清理热尾前必须确认对应区间已成功落到对象存储。
3. 合规删除路径（按租户/用户删除全部会话内容）需要覆盖两层存储。

---

## 4. 验收标准

- [ ] 刷新页面后，历史中的工具调用能看到完整参数与完整结果
- [ ] `AgentScopeContractController` 的 `toolInput` / `toolOutput` 字段对 harness 侧工具非空
- [ ] 超阈值的工具输出不进事件行正文，只留引用与预览，且引用可解析回原文
- [ ] turn 中断 / 工具超时场景下，已累积的部分被落盘或显式标记不完整，不静默丢失
- [ ] `builder_session_event` 有明确且已实现的保留策略
