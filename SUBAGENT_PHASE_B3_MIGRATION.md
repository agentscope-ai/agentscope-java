# Subagent Phase B-3 迁移指南

> 适用版本：`agentscope-core` 2.0.0-SNAPSHOT（含 Phase B-3 改造）
> 关联文档：[SUBAGENT_GAP_VS_OPENCODE.md](./SUBAGENT_GAP_VS_OPENCODE.md) 第 3 节修订版 ★★★ 3、实施 plan `~/.claude/plans/deep-mixing-adleman.md`

## 这次解决的问题

之前的 fire-and-forget subagent task（`timeout_seconds=0`）依赖 LLM 主动调用 `task_output` / `task_list` 才能看到结果。SYSTEM prompt 里通过三条硬规则约束 LLM 行为：

- "Never poll immediately after launching"
- "Never poll in a loop — task_output does not short-circuit"
- "Task status in conversation history is STALE"

规则越多越脆弱；模型容易在长会话或压缩后忽略，导致要么疯狂轮询要么忘记取结果。

Phase B-3 反转通信方向：**任务转入终态（COMPLETED / FAILED / CANCELLED）后，下一轮父 agent reasoning 时自动注入一条合成 `<system-reminder>` USER 消息**到对话历史，LLM 直接看到结果，不必查询。

## 行为变更

### 1. 新增 push 行为

任何 fire-and-forget 任务完成后，下一次父 agent reasoning：

1. `SubagentsMiddleware.onReasoning` 调用 `TaskRepository.findPendingDeliveries` 取所有 terminal+undelivered 任务。
2. 聚合为单条 `<system-reminder>` USER 消息，按完成时间升序列出每个任务的 `<task id="..." state="completed|error|cancelled" agent="...">` 块。
3. 写入父 `AgentState.contextMutable()`（持久化路径），同时也注入到本轮 `ReasoningInput` 让 LLM **本轮就看到**。
4. 下游 reasoning 成功完成后调 `markDelivered` 标记每个任务，下轮不再重投。

### 2. SYSTEM 模板瘦身

旧 SUBAGENT_SECTION 模板里的 5 条 "CRITICAL async task rules" 替换为简短的 "Background task flow" 3 步指引，明确说明"do not poll — completions are pushed to you automatically"。`task_output` / `task_cancel` / `task_list` 工具说明保留但语气从"必备工具"改为"按需 escape hatch"。

### 3. `buildTaskSummary` 过滤已投递

`### Async tasks (current session)` 段不再列出已投递的终态任务（它们已经在对话历史里以 `<system-reminder>` 形式存在）。**摘要只展示在跑的任务**，避免与历史冲突 + 让 10 条上限不被陈年任务占满。

### 4. `task_output` 也会 markDelivered

如果 LLM 主动调用 `task_output(block=true)` 等到结果，工具返回前会顺手 `markDelivered` 该任务，避免下一轮 push 重投同一结果。幂等：与自动 push 路径配合无冲突。

## API 变更

### `TaskRepository`（新增 3 个 default 方法 + 1 个 record，**向后兼容**）

```java
public interface TaskRepository {
    // ... 原有方法不变 ...

    /** Phase B-3: 返回 terminal + 未投递的任务，按完成时间升序。default 为空。 */
    default List<TaskDelivery> findPendingDeliveries(RuntimeContext rc, String sessionId) {
        return List.of();
    }

    /** 标记任务已投递；幂等。default 无操作。 */
    default void markDelivered(RuntimeContext rc, String sessionId, String taskId) {}

    /** 检查任务是否已投递。default 返回 false。 */
    default boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
        return false;
    }
}
```

新增 record：
```java
public record TaskDelivery(
        String taskId, String agentId, TaskStatus status,
        String result, String errorMessage, Instant completedAt) {}
```

**自定义 TaskRepository 实现无需改动**，default no-op 保证现有代码继续编译运行；只是不会享受 push 体验。

### `TaskRecord`（新增 1 个字段，**JSON 自动兼容**）

```java
private Instant deliveredAt;        // null = 未投递
public Instant getDeliveredAt();
public void setDeliveredAt(Instant t);
public boolean isDelivered();       // null check 简写
```

Jackson `@JsonInclude(NON_NULL)` + `@JsonIgnoreProperties(ignoreUnknown=true)` 已有，老 JSON 文件缺字段→null→未投递，自然 catch-up。

### `WorkspaceTaskRepository`

实现了上述 3 个 push 方法，独立 read-modify-write，**不**复用 `updateStatus` 通道（避免 heartbeat/orphan-sweeper 误擦字段）。

### `SubagentsMiddleware`

- `onReasoning` 加 delivery 阶段；
- `buildTaskSummary` 过滤已投递（公共方法签名不变，仅行为变更）；
- SYSTEM 模板字面量变更（删硬规则）。

### `TaskTool.taskOutput`

完成路径加 `taskRepository.markDelivered(...)` 调用。

## 兼容性表

| 项目 | 影响 |
|---|---|
| 自定义 TaskRepository | ✅ 无影响（default no-op） |
| 老 TaskRecord JSON | ✅ 自动兼容；首次启动后 catch-up 投递所有已 terminal 但 deliveredAt 缺的任务（**一次性**，可能略多） |
| DefaultTaskRepository | ⚠️ 不享受 push（保留 pull 行为）。如需 push，切换到 `WorkspaceTaskRepository` |
| Session mode `SubagentsMiddleware`（外部注入 tool） | ✅ 同样获得 push 行为（middleware 自身改造，与 spawn tool 类型无关） |
| HarnessAgent.builder()、WorkspaceMode、SubagentEventBus | ✅ 完全不动 |
| 非 ReActAgent | ✅ 跳过 push 写入（不可能持久化），fallback 到 SYSTEM 摘要旧路径 |

## 边界与已知行为

- **JVM 在 `next.apply` 完成与 `doOnComplete` 之间死**：下次启动会重投，AgentState 出现重复 reminder。无害（冗余文本）但用户可能见到同一通知出现两次。已记录为可接受 trade-off。
- **多任务封顶 10 条**：单条 reminder 至多列 `MAX_DELIVERIES_PER_REMINDER = 10` 个任务块，超出部分追加 "... and N more — call task_list() to inspect"。
- **idle agent**：任务在 agent 没在 reasoning 时完成 → 等用户下一次 prompt 触发 onReasoning 时统一投递。第一次投递会一次性 catch-up 全部。
- **大 result 不截断**：与 `task_output(block=true)` 行为一致。如果发现 token 爆炸，未来再做 preview / 截断（不在 B-3 范围）。
- **CANCELLED 任务**：无 result/error，渲染为固定文字 "Task was cancelled before producing a result."

## 测试

```bash
# Phase B-3 专项
mvn -pl agentscope-core test -Dspotless.check.skip=true \
    -Dtest='SubagentDeliveryTest,WorkspaceTaskRepositoryDeliveryTest'

# Phase A + B-3 联测 + 关键回归
mvn -pl agentscope-core test -Dspotless.check.skip=true \
    -Dtest='SubagentDeliveryTest,WorkspaceTaskRepositoryDeliveryTest,SubagentDeclarationPhaseATest,SubagentModeHiddenTest,SubagentSpecGeneratorTest,AgentGenerateToolTest,HarnessAgentTest,HarnessAgentModelStringTest,HarnessAgentSubagentStreamTest,HarnessAgentDynamicHookBuilderTest'
```

实测 B-3 新增 8 + 8 = 16 cases all green；Phase A 26 cases all green；HarnessAgent 主集合无回归。

## 下一步（Phase B-1 / C 预告）

Phase B-3 这条"跨线程→父 session 的投递通路"也是 Phase B-1（子 agent 活体 + 对话历史持久化）的 prior art：未来 `agent_send` 重启后续聊会通过类似机制把活体重建的事件回灌父侧。

Phase C-2（Permission ruleset 父→子继承）独立于 B-3，可随时启动。
