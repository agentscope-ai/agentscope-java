# ReActAgent 机制详解

> 一份方便遗忘时回顾的速查手册。涵盖 ReAct 循环、call vs stream、Hook 系统、多工具调用、Thinking 展示。

---

## 1. 架构概览

```
用户输入 (List<Msg>)
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│  ReActAgent                                             │
│                                                         │
│  call()  /  stream()                                    │
│    │                                                     │
│    ├─ PreCallEvent (Hook)                                │
│    │                                                     │
│    ├─ executeIteration(0)                                │
│    │    │                                                │
│    │    ├─ reasoning(0)     ← 调 LLM，拿思考+文本+工具调用  │
│    │    │    ├─ PreReasoningEvent                         │
│    │    │    ├─ model.stream() → ReasoningChunkEvent × N  │
│    │    │    └─ PostReasoningEvent                        │
│    │    │                                                │
│    │    ├─ [有 tool_calls?] → acting(0)                  │
│    │    │    ├─ PreActingEvent × N                        │
│    │    │    ├─ toolkit.callTools() → ActingChunkEvent    │
│    │    │    └─ PostActingEvent × N                       │
│    │    │                                                │
│    │    └─ [无 tool_calls?] → 返回最终 Msg                 │
│    │                                                     │
│    ├─ executeIteration(1) → reasoning(1) → ...           │
│    │    ...（循环直到 isFinished() = true）                │
│    │                                                     │
│    └─ PostCallEvent (Hook)                               │
│                                                         │
└─────────────────────────────────────────────────────────┘
  │
  ▼
Mono<Msg>  /  Flux<Event>
```

**核心数据流：** 用户消息 → PreCall → [Reasoning → Acting] × N → PostCall → 最终回复

---

## 2. ReAct 循环核心

### 2.1 入口

两个入口，底层是同一套循环：

```java
// AgentBase.java:851
public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
    return createEventStream(options, () -> call(msgs));
    //                          ^^^^^^^^^^^^^^^^^^^^^^^^
    //               stream 就是 call() + StreamingHook！
}
```

| | `call()` | `stream()` |
|---|---|---|
| 返回类型 | `Mono<Msg>` — 一个完整结果 | `Flux<Event>` — 实时事件流 |
| 底层循环 | ReAct 循环 | 同一个 ReAct 循环 |
| 中间过程 | 不可见 | 通过 StreamingHook 转发为 Event |
| 适用场景 | 批处理、API 调用、Webhook | SSE 流式推送、需要实时展示 |

### 2.2 reasoning() — 推理阶段

```java
// ReActAgent.java:572
private Mono<Msg> reasoning(int iter, boolean ignoreMaxIters) {
    // 1. 检查是否超过 maxIters
    // 2. 触发 PreReasoningEvent（Hook 可修改消息列表）
    // 3. 调用 model.stream() 获取 LLM 流式输出
    // 4. ReasonContext.processChunk() 逐 chunk 累积
    //    LLM 返回的 chunk 包含三种 ContentBlock：
    //      ├── reasoning_content → ThinkingBlock   (think 模式)
    //      ├── content           → TextBlock
    //      └── tool_calls        → ToolUseBlock
    // 5. 每个 chunk → notifyReasoningChunk() → ReasoningChunkEvent
    // 6. stream 结束 → buildFinalMessage() → 拼接完整 Msg(ASSISTANT)
    // 7. notifyPostReasoning() → PostReasoningEvent
    // 8. 判断：isFinished(msg)?
    //      true  → 返回 msg（无 tool_calls 或 TextBlock 结束）
    //      false → 进入 acting()
}
```

### 2.3 acting() — 行动阶段

```java
// ReActAgent.java:706
private Mono<Msg> acting(int iter) {
    // 1. extractPendingToolCalls() — 提取尚未执行过的工具调用
    // 2. 为空？ → executeIteration(iter + 1) 继续推理
    // 3. 不为空：
    //    a. 触发 PreActingEvent × N（Hook 可修改工具参数）
    //    b. toolkit.callTools([t1, t2, ...]) 执行工具
    //    c. 每个工具完成后 → PostActingEvent（Hook 可修改结果）
    //    d. 结果存入 memory
    // 4. 有 TOOL_SUSPENDED？ → 返回 Msg 等待用户执行
    // 5. 正常完成 → executeIteration(iter + 1) 继续推理
}
```

### 2.4 退出条件

ReAct 循环在以下情况退出：

- `isFinished(msg)` = true — LLM 不再返回 tool_calls，只返回文本
- `iter >= maxIters` — 达到最大迭代次数 → 进入 summarizing()
- `PostReasoningEvent.stopAgent()` — Hook 请求暂停（HITL）
- ToolSuspendException — 某些工具需要用户在外部执行
- InterruptedException — 外部中断

---

## 3. stream() 的事件流详解

### 3.1 Event 结构

```java
// Event.java
public class Event {
    EventType type;    // REASONING / TOOL_RESULT / SUMMARY
    Msg message;       // 携带具体内容的消息
    boolean isLast;    // true=最终完整消息, false=中间增量
    EventSource source; // 子 Agent 来源（顶层 Agent 为 null）
}
```

### 3.2 EventType

| 类型 | 含义 | isLast=false | isLast=true |
|---|---|---|---|
| `REASONING` | LLM 推理输出 | 增量 chunk（ThinkingBlock/TextBlock/ToolUseBlock） | 完整 Msg(ASSISTANT) |
| `TOOL_RESULT` | 工具执行结果 | 工具执行中间输出（若开启 ActingChunk） | 完整 ToolResultBlock |
| `SUMMARY` | 超 maxIters 后的总结 | 增量 chunk | 完整总结 |

### 3.3 StreamOptions 配置

```java
StreamOptions options = StreamOptions.builder()
    .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)  // 只听推理和工具事件
    .incremental(true)           // 增量模式（每次只发新增内容）
    .includeReasoningChunk(true) // 包含中间 chunk
    .includeReasoningResult(true)// 包含最终完整结果
    .includeActingChunk(false)   // 不包含工具执行的中间输出
    .build();
```

### 3.4 完整时间线示例

用户问 "帮我查北京天气和科技新闻"，LLM 返回 2 个 tool_calls：

```
时间 ──────────────────────────────────────────────────►

┌─ reasoning(iter=0) ────────────────────────────────────┐
│ Event(REASONING, Msg{ThinkingBlock}, isLast=false)     │  ← 思考过程 chunk
│ Event(REASONING, Msg{TextBlock "我来"}, isLast=false)  │  ← 文本增量
│ Event(REASONING, Msg{TextBlock "帮你查"}, isLast=false)│
│ Event(REASONING, Msg{ToolUse(get_weather)}, false)     │  ← 工具调用 chunk
│ Event(REASONING, Msg{ToolUse(get_news)}, false)        │
│ Event(REASONING, Msg{完整 Msg(ASSISTANT)}, isLast=true)│  ← 本次推理完成
└────────────────────────────────────────────────────────┘

┌─ acting(iter=0) ───────────────────────────────────────┐
│ Event(TOOL_RESULT, get_weather 结果, isLast=true)      │  ← 工具 1 完成
│ Event(TOOL_RESULT, get_news 结果, isLast=true)         │  ← 工具 2 完成
└────────────────────────────────────────────────────────┘

┌─ reasoning(iter=1) ────────────────────────────────────┐
│ Event(REASONING, Msg{TextBlock "北京晴天..."}, false)   │
│ Event(REASONING, Msg{完整回复}, isLast=true)            │  ← 最终回复
└────────────────────────────────────────────────────────┘
```

---

## 4. Hook 系统 — 生命周期拦截

### 4.1 设计理念

单一入口 + Java 模式匹配：

```java
public interface Hook {
    <T extends HookEvent> Mono<T> onEvent(T event);  // 所有事件走这一个方法
    default int priority() { return 100; }            // 优先级（越小越先执行）
    default List<Object> tools() { return List.of(); } // Hook 附带的自定义工具
}
```

消费方式：

```java
Hook myHook = new Hook() {
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreReasoningEvent e  -> { /* 修改输入 */ yield Mono.just(e); }
            case PostReasoningEvent e -> { /* 修改 LLM 输出 */ yield Mono.just(e); }
            case PostActingEvent e    -> { /* 修改工具结果 */ yield Mono.just(e); }
            case ReasoningChunkEvent e -> { /* 只读通知 */ yield Mono.just(e); }
            default -> Mono.just(event);
        };
    }
};
```

### 4.2 完整生命周期节点

```
Agent 启动
  │
  ├─ PreCallEvent         ✎ 修改输入消息列表
  │
  └─ ReAct 循环 (iter = 0, 1, 2, ...)
       │
       ├─ PreReasoningEvent    ✎ 修改送给 LLM 的消息、system prompt、GenerateOptions
       │
       ├─ ReasoningChunkEvent  ◉ 流式增量（ThinkingBlock/TextBlock/ToolUseBlock）
       │    (LLM 每返回一个 token 触发一次)
       │
       ├─ PostReasoningEvent   ✎ 修改/替换 LLM 输出
       │                         ★ stopAgent()    — 暂停等人工确认
       │                         ★ gotoReasoning() — 注入消息重新推理
       │
       ├─ [有 tool_calls?]
       │    │
       │    ├─ PreActingEvent       ✎ 修改工具调用参数
       │    │    (每个 tool_call 触发一次)
       │    │
       │    ├─ ActingChunkEvent     ◉ 工具执行中间输出（ToolEmitter 产生）
       │    │
       │    └─ PostActingEvent      ✎ 修改工具结果、脱敏、替换
       │         (每个 tool_call 触发一次)
       │         ★ stopAgent() — 工具执行完暂停
       │
       └─ [无 tool_calls?] → 退出循环
  │
  ├─ [iter >= maxIters?]
  │    ├─ PreSummaryEvent     ✎
  │    ├─ SummaryChunkEvent   ◉
  │    └─ PostSummaryEvent    ✎
  │
  └─ PostCallEvent         ✎ 修改最终返回的 Msg

✎ = 可修改事件（有 setter 方法）
◉ = 只读通知（无 setter）
```

### 4.3 各节点核心数据

| 事件 | 关键 getter | 关键 setter |
|---|---|---|
| PreCallEvent | `getInputMessages()` | `setInputMessages()` |
| PreReasoningEvent | `getInputMessages()`, `getSystemMessage()` | `setInputMessages()`, `setSystemMessage()` |
| PostReasoningEvent | `getReasoningMessage()` | `setReasoningMessage()`, `stopAgent()`, `gotoReasoning()` |
| PreActingEvent | `getToolUse()` | `setToolUse()` |
| PostActingEvent | `getToolUse()`, `getToolResult()` | `setToolResult()`, `stopAgent()` |
| PostCallEvent | `getResponse()` | `setResponse()` |

### 4.4 优先级规范

| 范围 | 用途 | 示例 |
|---|---|---|
| 0-50 | 系统级 | 认证注入、安全拦截 |
| 51-100 | 框架内置 | StreamingHook, CompactionHook |
| 101-500 | 业务 Hook | 审计、脱敏、限流、注入上下文 |
| 501-1000 | 观测 | 日志、Metrics、Tracing |

### 4.5 典型业务场景速查

| 需求 | 用哪个 Hook | 怎么做 |
|---|---|---|
| 注入用户画像/上下文 | PreReasoningEvent | `e.setInputMessages(追加后的列表)` |
| 实时推送 thinking 到前端 | ReasoningChunkEvent | 提取 `ThinkingBlock` |
| 实时推送文本增量 | ReasoningChunkEvent | 提取 `TextBlock` |
| 敏感工具暂停等审批 | PostReasoningEvent | 检查 `ToolUseBlock` → `e.stopAgent()` |
| 自动注入认证参数 | PreActingEvent | 修改 `ToolUseBlock.input` |
| 工具结果脱敏 | PostActingEvent | `e.setToolResult(masked)` |
| 工具结果限流 | PostActingEvent | 超限时 `setToolResult(error)` |
| 追加 token 用量信息 | PostCallEvent | `e.setResponse(modified)` |
| 错误告警 | ErrorEvent | 发送告警通知 |

---

## 5. 多工具调用机制

### 5.1 LLM 一次返回多个 tool_calls

LLM 在一次 reasoning 中可能返回多个 `ToolUseBlock`：

```
Msg(ASSISTANT)
  ├── ThinkingBlock("我需要同时查天气和新闻")
  ├── TextBlock("好的，我来帮你查")
  ├── ToolUseBlock(id=t1, name=get_weather, input={city:"北京"})
  └── ToolUseBlock(id=t2, name=get_news, input={category:"科技"})
```

### 5.2 acting() 批量提取

```java
// ReActAgent.java:1077
private List<ToolUseBlock> extractPendingToolCalls() {
    // 从最近的 ASSISTANT 消息中提取所有 ToolUseBlock
    // 过滤掉已有 ToolResultBlock 的（已执行过的）
    // 返回待执行的列表
}
```

### 5.3 工具执行

```java
// ReActAgent.java:823
toolkit.callTools(toolCalls, toolExecutionConfig, ...)
// 内部可以并发或顺序执行（取决于 Toolkit 实现）
// 每个工具完成后分别触发 PostActingEvent
```

### 5.4 循环继续

所有工具执行完毕 → `executeIteration(iter + 1)` → 新一轮 `reasoning()`，LLM 拿到工具结果后继续推理。

---

## 6. Thinking 内容的展示

### 6.1 ThinkingBlock 的产生

LLM（如 DeepSeek-R1、Claude）在 `reasoning_content` 字段中返回思考过程。`ReasoningContext.processChunk()` 将其累积为 `ThinkingBlock`。

### 6.2 call() 模式下

```java
// call() 返回 Mono<Msg>，只能拿到最终结果
Msg reply = agent.call(msgs).block();

// getTextContent() 只取 TextBlock，不包含 ThinkingBlock！
reply.getTextContent();  // ← 丢失了 thinking 内容

// 正确做法：遍历 ContentBlock
for (ContentBlock block : reply.getContent()) {
    if (block instanceof ThinkingBlock tb) {
        System.out.println("思考过程: " + tb.getThinking());
    }
}
```

**问题：** 即使拿到 ThinkingBlock，时机也不对 — 整个 Agent 执行完才返回，无法实时展示。

### 6.3 stream() 模式下

```java
agent.stream(msgs, options).subscribe(event -> {
    if (event.getType() == EventType.REASONING) {
        Msg msg = event.getMessage();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ThinkingBlock tb) {
                // 实时推送 thinking 内容到前端
                pushToFrontend("thinking", tb.getThinking());
            } else if (block instanceof TextBlock tb) {
                pushToFrontend("text_delta", tb.getText());
            }
        }
    }
});
```

**这才是正确做法：** `stream()` → `ReasoningChunkEvent` → 实时提取 `ThinkingBlock` → 前端逐字展示思考过程。

---

## 7. PlanNotebook — 结构化任务规划

### 7.1 一句话理解

PlanNotebook 是「给 Agent 装一个任务管理器」—— 让 LLM 把复杂任务拆成多个子任务，按顺序逐个完成，自动追踪进度。本质上是 **在 ReAct 循环之外套了一层 Plan 状态机**。

### 7.2 两层编排架构

```
┌─ PlanNotebook（计划层）─────────────────────────────────────┐
│                                                             │
│  Plan {                                                     │
│    name: "搭建个人博客网站"                                    │
│    subtasks: [                                              │
│      SubTask[0] "初始化项目"   TODO → IN_PROGRESS → DONE     │
│      SubTask[1] "设计数据库"   TODO → IN_PROGRESS → DONE     │
│      SubTask[2] "实现 API"    TODO → IN_PROGRESS → DONE     │
│      SubTask[3] "部署上线"    TODO                         │
│    ]                                                        │
│  }                                                          │
│                                                             │
│  PlanHintHook ── 在每个 PreReasoning 时自动注入当前计划提示     │
│                                                             │
└────────────────────┬────────────────────────────────────────┘
                     │ 通过 10 个 @Tool 方法暴露给 LLM
                     ▼
┌─ ReActAgent（执行层）───────────────────────────────────────┐
│                                                             │
│  reasoning() → LLM 看到 plan hint → 决定下一步操作             │
│     ├── create_plan       ← 首次创建计划                      │
│     ├── update_subtask_state  ← 标记 IN_PROGRESS              │
│     ├── finish_subtask    ← 完成后自动激活下一个                │
│     ├── revise_current_plan   ← 修改计划                      │
│     └── finish_plan       ← 全部完成                          │
│                                                             │
│  acting() → 执行业务工具   (非 PlanNotebook 工具)              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 7.3 PlanNotebook 提供的 10 个工具

| 工具 | 功能 | 关键行为 |
|---|---|---|
| `create_plan` | 创建新计划（name, desc, expectedOutcome, subtasks） | 覆盖旧 plan |
| `update_plan_info` | 修改计划名/描述/预期结果 | |
| `revise_current_plan` | 增/改/删子任务 | add/revise/delete |
| `update_subtask_state` | 改变子任务状态 | 只能顺序激活，同一时间只有一个 IN_PROGRESS |
| `finish_subtask` | 标记子任务完成 | **自动激活下一个子任务为 IN_PROGRESS** |
| `view_subtasks` | 查看子任务详情 | |
| `get_subtask_count` | 获取统计信息 | |
| `finish_plan` | 完成/放弃计划 | 存入历史 |
| `view_historical_plans` | 查看历史计划 | 可恢复 |
| `recover_historical_plan` | 恢复之前的计划 | 当前 plan 先存档 |

### 7.4 子任务状态机

```
TODO  ──update_subtask_state──► IN_PROGRESS ──finish_subtask──► DONE
  │                               │                                │
  └────── update_subtask_state ───┘                                │
                                                         （自动激活下一个）
                                                         下一个 TODO → IN_PROGRESS
```

**约束规则：**
- 同一时间只有一个子任务处于 `IN_PROGRESS`
- 必须按顺序执行（idx=1 的完成前不能激活 idx=2）
- `finish_subtask` 是唯一的正常完成路径（不能通过 `update_subtask_state` 直达 DONE）

### 7.5 Hint 注入机制（PlanHintHook）

PlanNotebook 不直接控制 Agent 行为，而是通过 **Hook 注入提示**来引导：

```
ReAct 循环的每次 PreReasoningEvent:
  │
  ├─ PlanHintHook 拦截
  │    ├─ 检查 currentPlan 状态
  │    └─ 调用 DefaultPlanToHint.generateHint()
  │         ├─ 无计划？ → "复杂任务请先 create_plan"
  │         ├─ 全部 TODO？ → "标记第一个子任务为 IN_PROGRESS 并开始执行"
  │         ├─ 某个 IN_PROGRESS？ → "子任务[N]正在执行，请完成它"
  │         ├─ 部分完成但无活跃？ → "请激活下一个子任务"
  │         └─ 全部完成？ → "请调用 finish_plan 总结"
  │
  └─ 把 hint 消息注入到 LLM 的输入消息列表中
```

### 7.6 完整执行流程示例

用户说 **"帮我搭建一个博客网站"**：

```
Round 1: 创建计划
  PreReasoning → hint: "复杂任务，先建计划"
  LLM 调用: create_plan("搭建博客", "搭建个人博客网站", "可访问的博客", [
    {name: "初始化项目", desc: "用框架创建项目骨架"},
    {name: "设计数据库", desc: "用户表和文章表"},
    {name: "实现 API", desc: "CRUD 接口"},
    {name: "部署上线", desc: "发布到云服务器"}
  ])
  → 4 个子任务全部 TODO

Round 2: 激活第一个子任务
  PreReasoning → hint: "所有子任务都待执行，请标记 SubTask[0] 为 IN_PROGRESS"
  LLM 调用: update_subtask_state(0, "in_progress")
  → SubTask[0] = IN_PROGRESS

Round 3: 执行 SubTask[0]
  PreReasoning → hint: "SubTask[0] '初始化项目' 正在执行中，请开始执行"
  LLM 调用: shell("npm create vite@latest")
  LLM 调用: finish_subtask(0, "项目骨架已创建，使用 Vite + React")
  → SubTask[0] = DONE, SubTask[1] 自动激活为 IN_PROGRESS

Round 4~N: 依次完成 SubTask[1] → [2] → [3]

最后一轮: 完成计划
  PreReasoning → hint: "所有子任务已完成，请调用 finish_plan"
  LLM 调用: finish_plan("done", "博客已部署到 https://myblog.example.com")
  → Plan 存入历史，currentPlan = null
```

### 7.7 关键设计要点

**自动激活：** `finish_subtask(0, outcome)` 内部自动把 `subtask[1]` 设为 `IN_PROGRESS`。这使得 Agent 不需要显式调用 `update_subtask_state` 来推进 —— 完成了一个，下一个自动开始。这也是 PlanNotebook 能自动化推进的核心设计。

**与 ReAct 循环的关系：** PlanNotebook 不是替代 ReAct 循环，而是在其之上。每一轮「下一步做什么」的决策仍然是 LLM 在 reasoning() 中做的，PlanNotebook 只是通过 hint + tool 提供了「上下文记忆 + 进度可视化」。

**历史恢复：** 完成的 Plan 存入 PlanStorage，可通过 `recover_historical_plan` 恢复。这在「上次中断了，现在继续」场景中很有用。

---

## 8. ReActAgent 中的上下文对象

ReActAgent 中有多个「上下文」对象，各自负责不同维度的状态。这里逐一说明它们的用途和业务场景。

### 8.1 全景图

```
                        ┌─────────────────┐
                        │  RuntimeContext  │  ← 每次 call() 外部传入
                        │   sessionId      │     (会话ID、用户ID、自定义属性)
                        │   userId         │
                        │   attributes     │
                        └───────┬─────────┘
                                │ 注入到 Hook 和 Tool 执行
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                       ▼
┌──────────────┐   ┌──────────────────┐   ┌──────────────────────┐
│   Memory     │   │  GenerateOptions  │   │ ToolExecutionContext │
│  (对话历史)   │   │  (LLM 调用参数)   │   │  (自定义 DI 对象)     │
│              │   │                  │   │                      │
│  消息列表    │   │  temperature     │   │  按优先级合并:         │
│  跨轮累积    │   │  maxTokens       │   │  call > agent > tk    │
└──────┬───────┘   │  modelName       │   └──────────────────────┘
       │           │  executionConfig │
       │           └────────┬─────────┘
       │                    │
       ▼                    ▼
┌──────────────┐   ┌──────────────────┐
│  Reasoning    │   │ ExecutionConfig  │
│  Context      │   │  (超时+重试策略)  │
│  (流式累积器) │   │                  │
│              │   │  timeout         │
│  thinkingAcc │   │  maxAttempts     │
│  textAcc     │   │  backoff         │
│  toolCallsAcc│   │  retryOn         │
└──────────────┘   └──────────────────┘
       │
       │  buildFinalMessage()
       ▼
  Msg(ASSISTANT)  →  Memory.addMessage()
```

### 8.2 各对象详解

#### ① Memory — 对话历史（跨轮累积）

```java
// 接口: io.agentscope.core.memory.Memory
// 默认实现: InMemoryMemory

// 生命周期: Agent 创建 → 持续累积 → Agent 销毁
// 每次 reasoning() 前取出全量消息送给 LLM
// 每次 reasoning/acting 后将新消息追加进去
```

**存什么：** 本轮对话的所有 `Msg` 列表（USER、ASSISTANT、TOOL 角色消息）

**典型业务场景：**
- 用户问 "帮我查天气" → memory 中有 1 条 USER 消息
- LLM 返回 tool_call → memory 中追加 ASSISTANT（含 ToolUseBlock）
- 工具返回结果 → memory 中追加 TOOL（含 ToolResultBlock）
- 下一轮 reasoning → 把 memory 中 3 条消息都发给 LLM，LLM 能看到完整上下文

```java
// 读取
List<Msg> history = agent.getMemory().getMessages();

// Hook 中读取（PreReasoningEvent 也暴露了）
event.getMemory().getMessages();
```

---

#### ② Session — 持久化存储（跨重启保存）

```java
// 接口: io.agentscope.core.session.Session
// 实现: InMemorySession / JsonSession / RedisSession（扩展包）

// 生命周期: 应用启动创建 → 跨多次 call() 复用 → 应用关闭
```

**存什么：** Agent 元数据、Memory、Toolkit 状态、PlanNotebook 状态的序列化数据

**什么时候用：**
- `agent.saveTo(session, sessionKey)` — 存盘
- `agent.loadFrom(session, sessionKey)` / `loadIfExists()` — 恢复

**典型业务场景：** 用户隔天回来继续对话，Memory 被完整恢复

```java
// 创建文件持久化 session
Session session = new JsonSession(Path.of("./agent-sessions/"));

// 每次对话前尝试恢复
String sessionKey = "user:bob:agent:assistant";
agent.loadIfExists(session, SessionKey.of(sessionKey));

// 对话结束后保存
agent.saveTo(session, SessionKey.of(sessionKey));
```

---

#### ③ RuntimeContext — 单次 call() 的元数据

```java
// io.agentscope.core.agent.RuntimeContext

// 生命周期: call() 开始前创建 → 整个 call() 期间可用 → call() 结束后丢弃
// 通过 Builder 构造: RuntimeContext.builder().sessionId("xxx").userId("bob").build()
```

**存什么：**

| 字段 | 说明 |
|---|---|
| `sessionId` | 对话存储标识 |
| `userId` | 当前用户标识（用于文件系统隔离） |
| `session` / `sessionKey` | Session 实例和 key（用于状态持久化） |
| `stringAttributes` | 自定义 String key → Object 属性 |
| `typedAttributes` | 自定义 Class → Map 属性（类型安全） |
| `toolExecutionContext` | 注入给 Tool 的自定义对象 |

**典型业务场景：**

```java
// 场景：多租户 SaaS，bob 调用 assistant
RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("sess_001")
    .userId("bob")
    .put("tenantId", "tenant-acme")     // 租户标识
    .put("traceId", "trace_abc123")     // 链路追踪
    .build();

agent.call(msgs, ctx);
// ctx 在整个 call() 执行期间可用
// Hook 通过 agent.getRuntimeContext() 获取
```

---

#### ④ ReasoningContext — 流式累积器（单次 reasoning 内部）

```java
// io.agentscope.core.agent.accumulator.ReasoningContext

// 生命周期: 每次 reasoning() 新建 → LLM stream 期间累积 → buildFinalMessage() 后丢弃
// 不跨轮共享，每次 reasoning 调用都 new 一个
```

**存什么：**

| 缓冲区 | 累积内容 |
|---|---|
| `thinkingAcc` | ThinkingBlock 的增量拼接 |
| `textAcc` | TextBlock 的增量拼接 |
| `toolCallsAcc` | ToolUseBlock 的增量拼接 |
| `inputTokens` / `outputTokens` | Token 用量统计 |

**典型业务场景：** 内部使用，业务代码不直接接触。但它产生的 `ReasoningChunkEvent` 中包含各缓冲区的当前快照，Hook 可以从中提取。

```java
// Hook 中获取 ReasoningChunkEvent
case ReasoningChunkEvent e -> {
    Msg chunk = e.getIncrementalChunk();
    // chunk 内容来自 ReasoningContext 的累积缓冲区
}
```

---

#### ⑤ ExecutionConfig — 超时与重试策略

```java
// io.agentscope.core.model.ExecutionConfig

// 有两套默认值:
//   MODEL_DEFAULTS:  timeout=5min, maxAttempts=3, backoff=2s→30s
//   TOOL_DEFAULTS:   timeout=5min, maxAttempts=1（工具不重试，有副作用）
```

**优先级（高 → 低）：** per-call > agent-level > toolkit-level > 系统默认

**典型业务场景：**

```java
// 场景 1: 生产环境调大超时
ReActAgent agent = ReActAgent.builder()
    .modelExecutionConfig(ExecutionConfig.builder()
        .timeout(Duration.ofMinutes(10))  // 复杂推理需要更长时间
        .maxAttempts(2)
        .build())
    .build();

// 场景 2: 允许某个工具重试（幂等操作）
// GenerateOptions 中 per-call 覆盖
GenerateOptions opts = GenerateOptions.builder()
    .executionConfig(ExecutionConfig.builder()
        .maxAttempts(3)
        .retryOn(e -> e.getMessage().contains("timeout"))  // 只在超时时重试
        .build())
    .build();
```

---

#### ⑥ GenerateOptions — LLM 调用参数

```java
// io.agentscope.core.model.GenerateOptions

// 生命周期: agent-level 在 build() 时设置 → 每次 reasoning() 通过 buildGenerateOptions() 合并
```

**存什么：**

| 类别 | 参数 |
|---|---|
| 连接 | `apiKey`, `baseUrl`, `modelName` |
| 生成控制 | `temperature`, `topP`, `maxTokens`, `maxCompletionTokens` |
| 工具控制 | `toolChoice`, `parallelToolCalls` |
| 思考控制 | `thinkingBudget`, `reasoningEffort` |
| 重试 | `executionConfig` |

**典型业务场景：**

```java
// 场景: 不同用户级别用不同模型
GenerateOptions vipOpts = GenerateOptions.builder()
    .modelName("qwen-max")       // VIP 用更好的模型
    .maxTokens(4096)
    .thinkingBudget(2048)
    .build();

// 场景: 在 Hook 中动态调整（PreReasoningEvent）
case PreReasoningEvent e -> {
    // 根据时间调整 temperature（夜间更保守）
    if (isNightTime()) {
        e.setGenerateOptions(GenerateOptions.builder()
            .temperature(0.3)
            .build());
    }
    yield Mono.just(e);
}
```

---

#### ⑦ ToolExecutionContext — 工具 DI 容器

```java
// io.agentscope.core.tool.ToolExecutionContext

// 生命周期: 与 Agent 绑定 → 每次工具执行时传给 Toolkit
// 三层合并: RuntimeContext.toolExecutionContext > agent.toolExecutionContext > toolkit.toolExecutionContext
```

**存什么：** 任意 Java 对象，工具方法通过参数类型自动注入

**典型业务场景：**

```java
// 场景: 工具方法需要当前用户的数据库连接
class MyTools {
    @Tool(name = "query_db", description = "查询数据库")
    public String queryDb(
        @ToolParam String sql,
        DatabaseConnection db  // ← 自动从 ToolExecutionContext 注入
    ) {
        return db.query(sql);
    }
}

// 注册到 agent
ToolExecutionContext toolCtx = ToolExecutionContext.builder()
    .add(new DatabaseConnection("jdbc:..."))
    .build();

ReActAgent agent = ReActAgent.builder()
    .toolExecutionContext(toolCtx)
    .build();
```

---

#### ⑧ InterruptContext — 中断上下文

```java
// io.agentscope.core.interruption.InterruptContext

// 生命周期: 中断发生时创建 → 传递给 handleInterrupt() → 用完丢弃
```

**存什么：**
- `source` — 中断来源（USER 手动停止 / SYSTEM 关机）
- `timestamp` — 中断时间
- `pendingToolCalls` — 未完成的工具调用列表

**典型业务场景：** shutdownManager 在 JVM 关闭时触发 SYSTEM 中断，hook 在 PostReasoningEvent 中通过 `stopAgent()` 触发 USER 中断。

---

### 8.3 各上下文对比总表

| 上下文 | 生命周期 | 存什么 | 谁创建 | 何时用 |
|---|---|---|---|---|
| **Memory** | Agent 的生命周期 | 对话消息历史 | Agent builder | 每次 reasoning 前读取，执行后追加 |
| **Session** | 跨重启 | 序列化的 Memory/State | 外部应用 | 启动时 loadFrom，结束时 saveTo |
| **RuntimeContext** | 单次 call() | sessionId/userId/自定义属性 | 调用方 | call() 全程，Hook 和 Tool 均可访问 |
| **ReasoningContext** | 单次 reasoning() | 流式 chunk 累积缓冲 | ReActAgent | LLM stream 期间，buildFinalMessage 后销毁 |
| **ExecutionConfig** | 配置级 | 超时+重试参数 | Builder/GenerateOptions | 模型调用和工具执行的容错控制 |
| **GenerateOptions** | 配置级 | LLM 连接和生成参数 | Builder/PreReasoningHook | 每次 reasoning() 送给 model.stream() |
| **ToolExecutionContext** | 配置级 | 自定义 DI 对象 | Builder | 工具方法执行时的参数自动注入 |
| **InterruptContext** | 中断时刻 | 中断原因+未完成任务 | 中断机制 | handleInterrupt() 判断后续行为 |

### 8.4 上下文与业务场景对照

| 场景 | 用什么上下文 |
|---|---|
| 用户隔天回来继续聊天 | `Session.loadFrom()` 恢复 Memory |
| 多租户 SaaS，每个租户不同数据库 | `ToolExecutionContext` 注入 DataSource |
| 需要追踪每次 LLM 调用的 trace | `RuntimeContext.put("traceId", id)` |
| Agent 执行超时自动重试 | `ExecutionConfig.maxAttempts(3)` |
| VIP 用户用更好的模型 | Goose `PreReasoningEvent` 动态改 `GenerateOptions.modelName` |
| 需要统计一次对话用了多少 token | `ReasoningContext` 累积的 inputTokens/outputTokens |
| JVM 关闭时优雅停止 Agent | `InterruptContext(source=SYSTEM)` → 保存状态后退出 |
| 用户点击"停止生成"按钮 | `agent.interrupt()` → `InterruptContext(source=USER)` |

---

## 9. 每个 Event 的数据结构详解

Hook 系统中的每个事件类型携带的数据都不一样。下面按继承层次逐层拆解：

### 9.1 类继承层次

```
HookEvent (abstract sealed)
  ├── PreCallEvent
  ├── PostCallEvent
  ├── ReasoningEvent (abstract sealed)
  │     ├── PreReasoningEvent
  │     ├── ReasoningChunkEvent
  │     └── PostReasoningEvent
  ├── ActingEvent (abstract sealed)
  │     ├── PreActingEvent
  │     ├── ActingChunkEvent
  │     └── PostActingEvent
  ├── SummaryEvent (abstract sealed)
  │     ├── PreSummaryEvent
  │     ├── SummaryChunkEvent
  │     └── PostSummaryEvent
  └── ErrorEvent
```

### 9.2 基类 —— 所有事件都有的字段

```java
// HookEvent (所有事件的父类)
getAgent()         // Agent 实例（可强转为 ReActAgent）
getMemory()        // Agent 的内存（对话历史列表），ReActAgent 实现返回 Memory，其他 Agent 返回 null
getType()          // HookEventType 枚举：PRE_CALL / POST_CALL / PRE_REASONING / ...
getTimestamp()     // 事件创建时间戳 (ms)
getSystemMessage() // 当前的 system prompt ★ 下文详述
setSystemMessage() // 替换 system prompt
appendSystemContent(String)      // 追加文本到 system prompt
appendSystemContent(ContentBlock)// 追加 ContentBlock 到 system prompt
```

> **system message 生命周期**：每次 `call()` 开始时从 Agent 的 `sysPrompt` 复制一份作为基准。后续每次 `PreReasoningEvent` / `PreSummaryEvent` 都会从基准重新复制，所以 Hook 在每轮迭代中的 appendSystemContent 不会跨轮累积。

### 9.3 ReasoningEvent（推理事件基类）

```java
// 继承自 HookEvent，新增:
getModelName()        // 模型名称，如 "qwen-plus"、"gpt-4"
getGenerateOptions()  // GenerateOptions（temperature, maxTokens, topP 等）
```

---

### 9.4 各具体事件数据拆解

#### PreCallEvent ✎ 可修改

```
触发时机: Agent.call() 开始，所有 ReAct 循环之前
触发次数: 每次 call() 一次
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | 对话历史 |
| `getSystemMessage()` | Msg | 当前 system prompt ✎ |
| `setSystemMessage()` | | 替换 system prompt |
| `appendSystemContent()` | | 追加 system 内容 |
| **`getInputMessages()`** | `List<Msg>` | **送给 Agent 的原始用户消息** ✎ |
| **`setInputMessages()`** | | **修改输入消息列表** |

**数据样貌：**
```
PreCallEvent {
  inputMessages = [Msg(USER, "帮我部署到生产环境")]
  systemMessage = Msg(SYSTEM, "你是一个部署助手...")
  memory = [ (空或上次对话的历史) ]
}
```

---

#### PreReasoningEvent ✎ 可修改

```
触发时机: 每次 reasoning() 调用前（ReAct 循环中可能多次触发）
触发次数: 每轮迭代一次
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | **当前对话历史（包含之前所有轮次的消息）** |
| `getModelName()` | String | 模型名称 |
| `getGenerateOptions()` | GenerateOptions | 当前生成参数 |
| `getSystemMessage()` | Msg | 本轮 system prompt ✎ |
| `appendSystemContent()` | | 追加内容到 system prompt |
| **`getInputMessages()`** | `List<Msg>` | **即将发给 LLM 的完整消息列表** ✎ |
| **`setInputMessages()`** | | **增删改消息**（如注入 hint、追加上下文） |
| **`getEffectiveGenerateOptions()`** | GenerateOptions | 获取被 Hook 覆盖后的 GenerateOptions |
| **`setGenerateOptions()`** | | **动态修改 LLM 调用参数** |

**数据样貌（第 3 轮迭代时）：**
```
PreReasoningEvent {
  inputMessages = [
    Msg(SYSTEM, "你是一个部署助手..."),
    Msg(USER, "帮我部署到生产环境"),
    Msg(ASSISTANT, [TextBlock("好的"), ToolUseBlock(get_servers)]),
    Msg(TOOL, [ToolResultBlock("server1, server2")]),
  ]
  modelName = "qwen-plus"
  generateOptions = { temperature: 0.7, maxTokens: 4096 }
  memory = [ 同上 4 条 ]
}
```

---

#### ReasoningChunkEvent ◉ 只读

```
触发时机: LLM 流式返回每一个 chunk 时
触发次数: 每轮 reasoning 中可能 N 次（每个 token 或每几个 token）
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | 对话历史 |
| `getModelName()` | String | 模型名称 |
| `getGenerateOptions()` | GenerateOptions | 生成参数 |
| **`getIncrementalChunk()`** | `Msg` | **本次增量（仅本轮新增的内容）** |
| **`getAccumulated()`** | `Msg` | **目前为止的全量累积** |

> `getIncrementalChunk()` vs `getAccumulated()`：
> - `incrementalChunk`：只包含本次 chunk 新增的 ContentBlock，适用于「追加显示」
> - `accumulated`：包含从本轮 reasoning 开始到现在的所有 ContentBlock，适用于「替换显示」

**★ 核心：Msg 内部的 ContentBlock 可能是三种类型混在一起**

```
ReasoningChunkEvent (第 5 个 chunk 到达时):

  incrementalChunk = Msg(ASSISTANT) {
    content = [
      ToolUseBlock {                      ← 模型决定调用工具
        id: "call_1", name: "get_weather",
        input: { city: "北京" }
      }
    ]
  }

  accumulated = Msg(ASSISTANT) {
    content = [
      ThinkingBlock { "用户想知道北京天气，我需要..." },  ← 思考过程
      TextBlock { "我来帮你查一下北京天气" },             ← 文本回复
      ToolUseBlock { ... },                              ← 工具调用
    ]
  }
```

**一个 chunk 里只有一种 Block**（ThinkingBlock / TextBlock / ToolUseBlock 不会混在同一个 chunk 中），但 accumulated 包含所有已累积的 Block。

**前端展示典型用法：**
```java
case ReasoningChunkEvent e -> {
    for (ContentBlock block : e.getIncrementalChunk().getContent()) {
        switch (block) {
            case ThinkingBlock tb ->
                pushToFrontend("thinking", tb.getThinking());  // 思考面板
            case TextBlock tb ->
                pushToFrontend("text_delta", tb.getText());    // 主文本区
            case ToolUseBlock tub ->
                pushToFrontend("tool_call", tub.getName());    // 工具调用提示
            default -> {}
        }
    }
}
```

---

#### PostReasoningEvent ✎ 可修改

```
触发时机: 本轮 LLM streaming 结束，完整 Msg(ASSISTANT) 拼接完成后
触发次数: 每轮 reasoning 一次
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | 对话历史 |
| `getModelName()` | String | 模型名称 |
| `getGenerateOptions()` | GenerateOptions | 生成参数 |
| **`getReasoningMessage()`** | `Msg` | **LLM 本轮完整输出（含 Thinking+Text+ToolUse）** ✎ |
| **`setReasoningMessage()`** | | **修改/替换 LLM 输出** |
| **`stopAgent()`** | | **★ 暂停执行，等人工确认后恢复** |
| `isStopRequested()` | boolean | 是否已请求暂停 |
| **`gotoReasoning()`** | | **★ 不执行工具，直接回到 reasoning 再来一轮** |
| `isGotoReasoningRequested()` | boolean | 是否已请求重推理 |
| `getGotoReasoningMsgs()` | List\<Msg\> | 回到 reasoning 前要注入的消息 |

**数据样貌：**
```
PostReasoningEvent {
  reasoningMessage = Msg(ASSISTANT) {
    content = [
      ThinkingBlock { "需要调用部署工具..." },
      TextBlock { "好的，我来部署" },
      ToolUseBlock { name: "deploy", input: {env: "prod"} },  ← 敏感操作！
    ]
  }
  stopRequested = false
}
// Hook 检测到 deploy → e.stopAgent() → Agent 暂停，等管理员审核
```

---

#### PreActingEvent ✎ 可修改

```
触发时机: 每个工具执行前
触发次数: 每轮 acting 中 N 次（每个待执行的 tool_call 一次）
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | 对话历史 |
| `getToolkit()` | Toolkit | 工具集 |
| **`getToolUse()`** | `ToolUseBlock` | **待执行的工具调用** ✎ |
| **`setToolUse()`** | | **修改工具参数**（如注入 token、改文件名） |

**数据样貌：**
```
PreActingEvent {
  toolUse = ToolUseBlock {
    id: "call_1",
    name: "deploy",
    input: { env: "prod", version: "latest" }
  }
  toolkit = <包含所有已注册工具的 Toolkit>
}
```

---

#### ActingChunkEvent ◉ 只读

```
触发时机: 工具通过 ToolEmitter 推送中间结果时
触发次数: 每个工具执行期间可能 0~N 次
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | 对话历史 |
| `getToolkit()` | Toolkit | 工具集 |
| `getToolUse()` | ToolUseBlock | 当前正在执行的工具 |
| **`getChunk()`** | `ToolResultBlock` | **工具执行中间输出** |

> 区别于 PostActingEvent：这里的 chunk 是中间过程，**不会**发送给 LLM。只有最终 return 值（PostActingEvent 的 toolResult）才会发给 LLM。

**数据样貌：**
```
ActingChunkEvent {
  toolUse = ToolUseBlock { name: "shell", input: {cmd: "npm install"} }
  chunk = ToolResultBlock {
    output: "Installing packages... (1/42) react@18.0.0"
    isError: false, isSuspended: false
  }
}
// → 前端实时展示 "正在安装依赖... (1/42)"
```

---

#### PostActingEvent ✎ 可修改

```
触发时机: 每个工具执行完毕时
触发次数: 每轮 acting 中 N 次（每个完成的工具一次）
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | 对话历史 |
| `getToolkit()` | Toolkit | 工具集 |
| `getToolUse()` | ToolUseBlock | 原始工具调用 |
| **`getToolResult()`** | `ToolResultBlock` | **工具执行结果** ✎ |
| **`setToolResult()`** | | **修改/替换结果**（脱敏、截断、注入） |
| `getToolResultMsg()` | Msg | 工具结果对应的 TOOL 消息 ✎ |
| `setToolResultMsg()` | | 修改 TOOL 消息 |
| **`stopAgent()`** | | **★ 执行完后暂停** |
| `isStopRequested()` | boolean | 是否已请求暂停 |

**数据样貌：**
```
PostActingEvent {
  toolUse = ToolUseBlock { name: "deploy", input: {env: "prod"} }
  toolResult = ToolResultBlock {
    output: "Deploy success. URL: https://prod.example.com\nAPI_KEY=sk-1234abcd"
    isError: false, isSuspended: false
  }
}
// Hook 检测到 API_KEY 泄露 → 脱敏 → e.setToolResult(maskedResult)
```

---

#### PreSummaryEvent ✎ 可修改

```
触发时机: 迭代次数达到 maxIters 仍未完成，开始总结前
触发次数: 最多一次（如果有总结阶段）
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | 对话历史 |
| `getModelName()` | String | 模型名称 |
| `getGenerateOptions()` | GenerateOptions | 生成参数 |
| `getSystemMessage()` | Msg | system prompt ✎ |
| **`getInputMessages()`** | `List<Msg>` | **送给 LLM 做总结的消息** ✎ |
| **`setInputMessages()`** | | 修改总结输入 |
| **`getEffectiveGenerateOptions()`** | GenerateOptions | 覆盖后的 GenerateOptions |
| **`setGenerateOptions()`** | | 动态修改总结参数 |
| `getMaxIterations()` | int | 配置的最大迭代次数 |
| `getCurrentIteration()` | int | 触发总结时的实际迭代次数 |

---

#### SummaryChunkEvent ◉ 只读

```
触发时机: 总结阶段 LLM 流式返回每个 chunk 时
结构: 与 ReasoningChunkEvent 相同（incrementalChunk + accumulated）
内容: 只含 TextBlock（总结通常不产生 ThinkingBlock 和 ToolUseBlock）
```

---

#### PostSummaryEvent ✎ 可修改

```
触发时机: 总结完成时
触发次数: 最多一次
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | 对话历史 |
| `getModelName()` | String | 模型名称 |
| `getGenerateOptions()` | GenerateOptions | 生成参数 |
| **`getSummaryMessage()`** | `Msg` | **总结消息** ✎ |
| **`setSummaryMessage()`** | | 修改总结内容 |
| `stopAgent()` | | 暂停 |
| `isStopRequested()` | boolean | |

---

#### PostCallEvent ✎ 可修改

```
触发时机: 整个 Agent.call() 完成，即将返回给调用方
触发次数: 每次 call() 一次（最后）
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | 对话历史 |
| `getSystemMessage()` | Msg | system prompt |
| **`getFinalMessage()`** | `Msg` | **最终返回给用户的 Msg** ✎ |
| **`setFinalMessage()`** | | **修改/替换最终回复** |

---

#### ErrorEvent ◉ 只读

```
触发时机: Agent 执行过程中出现异常时
触发次数: 0~1 次
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `getAgent()` | Agent | Agent 实例 |
| `getMemory()` | Memory | 对话历史 |
| **`getError()`** | `Throwable` | **异常对象** |

---

### 9.5 ChannelRouter 路由验证

```
触发时机: 每个 Channel 消息入站时
触发次数: 每个入站消息一次
```

**说明：** 这不是 Hook 事件，而是 Channel 级别的路由验证。控制哪些 Channel 能访问哪些 Agent。

| 字段 | 类型 | 说明 | 默认值 |
|---|---|---|---|
| `channelId` | `String` | 消息来源渠道，如 `"feishu-rd"` | 必填 |
| `channelType` | `String` | 渠道类型，如 `"feishu"`, `"github"` | 必填 |
| `channelDescription` | `String` | 渠道描述，如 "飞书研发部群聊" | `null` |
| **`algorithm`** | `String` | **验证算法，支持 `GLOB`** | `"GLOB"` |
| **`allowedAgentIds`** | `List<String>` | **允许的 Agent ID 列表** | `["*"]` |
| **`deniedAgentIds`** | `List<String>` | **拒绝的 Agent ID 列表** | `[]` |
| `extra` | `Map<String, Object>` | 扩展属性 | `{}` |

> `allowedAgentIds` / `deniedAgentIds` 支持 Glob 通配符，如 `"admin-*"` 匹配所有 admin 开头的 Agent。`denied` 优先级高于 `allowed`。

### 9.6 典型业务场景下该查看哪些字段

| 你想做什么 | 用哪个 Event | 取哪些字段 |
|---|---|---|
| 前端展示 "思考中..." | ReasoningChunkEvent | `getIncrementalChunk().getContent()`, 过滤 `ThinkingBlock` |
| 前端逐字显示回复 | ReasoningChunkEvent | `getIncrementalChunk().getContent()`, 过滤 `TextBlock` |
| 显示 "调用工具: deploy" | ReasoningChunkEvent | `getIncrementalChunk().getContent()`, 过滤 `ToolUseBlock` |
| 显示工具执行进度 | ActingChunkEvent | `getChunk().getOutput()` |
| 工具执行完成通知 | PostActingEvent | `getToolUse().getName()` + `getToolResult()` |
| 注入用户画像到 prompt | PreReasoningEvent | `setInputMessages()` 追加 Msg |
| 动态切换模型 | PreReasoningEvent | `setGenerateOptions()` |
| 敏感工具暂停等审批 | PostReasoningEvent | 检查 ToolUseBlock → `stopAgent()` |
| 工具结果脱敏 | PostActingEvent | `getToolResult()` → 修改 → `setToolResult()` |
| Token 用尽自动总结 | PreSummaryEvent | `getCurrentIteration()` / `getMaxIterations()` |
| 修改最终回复 | PostCallEvent | `getFinalMessage()` → 修改 → `setFinalMessage()` |
| 错误告警 | ErrorEvent | `getError().getMessage()` |

### 9.7 字段速查总表

```
                    Agent Memory Model  GenOpt  Msgs   ToolUse Result Chunk   Summary SysMsg Err  Stop GoReason
PreCallEvent         ✓     ✓      -      -      ✎     -       -      -       -       ✎      -    -     -
PostCallEvent        ✓     ✓      -      -      -     -       -      -       -       ✎      -    -     -
PreReasoningEvent    ✓     ✓      ✓      ✓      ✎     -       -      -       -       ✎      -    -     -
ReasoningChunkEvent  ✓     ✓      ✓      ✓      -     -       -      ◉(累+增) -       -      -    -     -
PostReasoningEvent   ✓     ✓      ✓      ✓      -     -       -      -       -       -      -    ✎     ✎
PreActingEvent       ✓     ✓      -      -      -     ✎       -      -       -       -      -    -     -
ActingChunkEvent     ✓     ✓      -      -      -     ✓       -      ◉       -       -      -    -     -
PostActingEvent      ✓     ✓      -      -      -     ✓       ✎      -       -       -      -    ✎    -
PreSummaryEvent      ✓     ✓      ✓      ✓      ✎     -       -      -       -       ✎      -    -     -
SummaryChunkEvent    ✓     ✓      ✓      ✓      -     -       -      ◉(累+增) -       -      -    -     -
PostSummaryEvent     ✓     ✓      ✓      ✓      -     -       -      -       ✎       -      -    ✎    -
ErrorEvent           ✓     ✓      -      -      -     -       -      -       -       -      ✓    -     -

✎ = 可修改（有 setter）   ◉ = 只读携带数据   ✓ = 只读元信息   - = 无此字段
累+增 = incrementalChunk + accumulated
```

---

## 10. 关键类路径索引

| 类 | 路径 |
|---|---|
| ReActAgent | `agentscope-core/.../ReActAgent.java` |
| AgentBase | `agentscope-core/.../agent/AgentBase.java` |
| Hook 接口 | `agentscope-core/.../hook/Hook.java` |
| StreamingHook | `agentscope-core/.../agent/StreamingHook.java` |
| Event | `agentscope-core/.../agent/Event.java` |
| EventType | `agentscope-core/.../agent/EventType.java` |
| StreamOptions | `agentscope-core/.../agent/StreamOptions.java` |
| ReasoningContext | `agentscope-core/.../agent/accumulator/ReasoningContext.java` |
| RuntimeContext | `agentscope-core/.../agent/RuntimeContext.java` |
| ExecutionConfig | `agentscope-core/.../model/ExecutionConfig.java` |
| GenerateOptions | `agentscope-core/.../model/GenerateOptions.java` |
| Memory | `agentscope-core/.../memory/Memory.java` |
| Session | `agentscope-core/.../session/Session.java` |
| ToolExecutionContext | `agentscope-core/.../tool/ToolExecutionContext.java` |
| InterruptContext | `agentscope-core/.../interruption/InterruptContext.java` |
| PlanNotebook | `agentscope-core/.../plan/PlanNotebook.java` |
| PlanHintHook | `agentscope-core/.../plan/hint/DefaultPlanToHint.java` |
| HookEvent (基类) | `agentscope-core/.../hook/HookEvent.java` |
| ReasoningEvent | `agentscope-core/.../hook/ReasoningEvent.java` |
| ActingEvent | `agentscope-core/.../hook/ActingEvent.java` |
| PreReasoningEvent | `agentscope-core/.../hook/PreReasoningEvent.java` |
| ReasoningChunkEvent | `agentscope-core/.../hook/ReasoningChunkEvent.java` |
| PostReasoningEvent | `agentscope-core/.../hook/PostReasoningEvent.java` |
| PreActingEvent | `agentscope-core/.../hook/PreActingEvent.java` |
| ActingChunkEvent | `agentscope-core/.../hook/ActingChunkEvent.java` |
| PostActingEvent | `agentscope-core/.../hook/PostActingEvent.java` |
| PreSummaryEvent | `agentscope-core/.../hook/PreSummaryEvent.java` |
| SummaryChunkEvent | `agentscope-core/.../hook/SummaryChunkEvent.java` |
| PostSummaryEvent | `agentscope-core/.../hook/PostSummaryEvent.java` |
| PreCallEvent | `agentscope-core/.../hook/PreCallEvent.java` |
| PostCallEvent | `agentscope-core/.../hook/PostCallEvent.java` |
| ErrorEvent | `agentscope-core/.../hook/ErrorEvent.java` |
| Msg | `agentscope-core/.../message/Msg.java` |
| ThinkingBlock | `agentscope-core/.../message/ThinkingBlock.java` |
| TextBlock | `agentscope-core/.../message/TextBlock.java` |
| ToolUseBlock | `agentscope-core/.../message/ToolUseBlock.java` |
| ToolResultBlock | `agentscope-core/.../message/ToolResultBlock.java` |
| HarnessAgent | `agentscope-harness/.../agent/HarnessAgent.java` |

---

## 11. 一句话总结

```
call()  = 完整 ReAct 循环 → Mono<Msg>（等全部结束，一次性拿结果）

stream() = call() + StreamingHook → Flux<Event>
           （同一个循环，但中间过程实时暴露）

Hook    = 在 ReAct 循环的 10+ 个节点上植入业务逻辑
          switch(event) { case PreReasoningEvent → ... }

多工具  = reasoning() 返回多个 ToolUseBlock
          → acting() 提取 → 逐个执行 → 逐个 PostActingEvent
          → 执行完回到 reasoning()

Thinking = LLM 的 reasoning_content → ThinkingBlock
           call() 下只能等全部结束再拿
           stream() 下实时推送 ReasoningChunkEvent

PlanNotebook = ReAct 循环外层的 Plan 状态机
                LLM 拆任务 → 顺序执行 → 自动激活下一个 → 完成总结
                通过 PlanHintHook 注入计划提示，LLM 用 10 个工具管理进度

上下文对象:
  Memory        = 对话历史（跨轮累积）
  Session       = 持久化存储（跨重启）
  RuntimeContext= 单次 call 元数据（sessionId/userId/自定义属性）
  ReasoningCtx  = 流式累积缓冲（单次 reasoning 内部，打完即弃）
  ExecutionConfig= 超时重试策略（三层优先级: call > agent > toolkit > 默认）
  GenerateOptions= LLM 调用参数（模型名/temperature/thinkingBudget）
  ToolExecCtx   = 工具 DI 容器（自动注入到 @Tool 方法参数）
  InterruptCtx  = 中断原因记录（USER 停止 / SYSTEM 关机）

Event 数据 = 每个 Hook 事件携带不同字段，按类型区分:
  ReasoningChunk: incrementalChunk(含 ThinkingBlock/TextBlock/ToolUseBlock) + accumulated
  PostReasoning:  完整的 Msg(ASSISTANT) → 可 stopAgent 暂停, gotoReasoning 重试
  PostActing:     ToolUseBlock + ToolResultBlock → 可 stopAgent 暂停
  PostCall:        最终 Msg → 可修改最终回复
```
