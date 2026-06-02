---
title: "子 Agent（Subagent）"
description: "声明子 agent、同步/后台调用、自动反向通知、远程子 agent、流式转发"
---

## 作用

让主 agent 把"可独立处理、上下文重、可并行"的任务委派出去，避免主线程膨胀。每个子 agent 都是一个临时实例（本地的 `HarnessAgent` 或远程 stub），跑自己的会话，结果通过工具返回给父 agent。

## 一个最小例子

最简单的用法：把子 agent 的 spec 写到工作区里就行。文件名就是 `agent_id`：

`workspace/subagents/reviewer.md`：

```markdown
---
description: 代码审查专家。当用户需要 review PR、找代码问题、检查代码规范时使用。
---

你是一个专注代码评审的子 agent。请按以下流程工作：
1. 先 read_file / grep_files 收集上下文
2. 给出按文件 / 行号的具体建议
3. 末尾给一个 1-5 的总体评分
```

然后主 agent 就能在推理时调用：

```
agent_spawn agent_id="reviewer" task="review 这次 PR 的所有改动"
```

不需要做任何注册。

## 几种声明方式

支持下面三类来源，构建时合并：

| 方式 | 适用 | 怎么配 |
|------|------|--------|
| 内置 `general-purpose` | 通用兜底（镜像主 agent 能力） | 总是有，不需要配 |
| 工作区 spec 文件 | 项目特有的、能版本控制的 | `workspace/subagents/<id>.md` |
| 编程式声明 | 跑时才能确定（远程、动态参数） | `builder.subagent(SubagentDeclaration.builder()...)` |

### 工作区 spec 文件

非递归扫 `workspace/subagents/*.md`，文件名（去掉 `.md`）就是 `agent_id`，**不要**在 front matter 里再写 `name`。

```markdown
---
description: 代码评审专家     # 必填，agent 选择是否委派的关键依据
workspace:
  mode: isolated              # 默认 isolated；shared 表示和父共享工作区
  path: ./defs/reviewer       # 可选；不写就用默认子目录
model: openai:gpt-4o-mini     # 可选；不写就继承父 agent
steps: 8                      # 可选；这个子 agent 单次最多迭代次数
temperature: 0.2              # 可选；覆盖父的 GenerateOptions
top_p: 0.95                   # 可选
hidden: false                 # true 时不出现在 agent 可见列表（仍可程序化 spawn）
mode: subagent                # primary / subagent / all，默认 all；primary 不允许被 spawn
tools: [read_file, grep_files]   # 可选；继承工具的白名单
---

你是一个专注代码评审的子 agent。
```

### 编程式声明

```java
HarnessAgent.builder()
    .name("orchestrator")
    .model(model)
    .workspace(workspace)
    .subagent(SubagentDeclaration.builder()
        .name("reviewer")
        .description("代码审查专家")
        .workspace(Path.of("./defs/reviewer"))
        .workspaceMode(WorkspaceMode.ISOLATED)
        .model("qwen3-max")
        .steps(8)
        .tools(List.of("read_file", "grep_files"))
        .build())
    .subagent(SubagentDeclaration.builder()
        .name("remote-researcher")
        .description("远端调研子 agent")
        .url("http://agent-task-server:8080")     // 远程子 agent
        .headers(Map.of("Authorization", "Bearer xxx"))
        .build())
    .build();
```

三种来源互斥：`workspace(...)`、`inlineAgentsBody(...)`、`url(...)` **三选一**。

### 内置 `general-purpose`

不需要写声明文件，总是可用。它的角色是"通用兜底"——能力和主 agent 一致（同样的模型、工具、技能），共享主工作区。适合"主 agent 想隔离上下文跑一个子任务但又懒得专门写 spec"。

## ISOLATED vs SHARED

`workspaceMode` 决定子 agent 的工作区怎么算：

- **ISOLATED**（默认）：子 agent 有自己独立的工作区（如果声明里 `workspace.path` 没写，框架会自动开一个子目录）。子 agent 的运行时状态按"父 sessionId × 用户"分桶——同一用户在不同对话里 spawn 同名子 agent 也互不污染。
- **SHARED**：子 agent 直接用主工作区。适合子 agent 的输出会被父立即读到的情况（例如 `general-purpose`）。

## 同步还是后台？

主 agent 通过 `agent_spawn` 创建子 agent，关键是 `timeout_seconds`：

- `timeout_seconds > 0`（默认 30，最大 600）—— **同步**调用，主 agent 在这一步 block 等待结果，结果作为工具结果返回。
- `timeout_seconds = 0` —— **后台**调用，立即返回一个 `task_id`，子 agent 在后台跑。

### 后台任务自动反向通知

后台任务跑完了，**主 agent 不需要轮询**——下一次推理开始前，框架会把已完成的任务结果作为系统提醒注入对话末尾：

```
<system-reminder>
后台任务已交付：
- task_id=xxx，agent=research-analyst，status=COMPLETED
  结果摘要：...
</system-reminder>
```

主 agent 看到这条 reminder 自然地回应或继续行动。这意味着你**不需要**在 prompt 里写"记得调 task_output 轮询"——那是旧版本的做法。

> `task_output` / `task_cancel` / `task_list` 这些工具还在，但仅作"逃生口"或人工调试时用。生产 prompt 里不应该出现轮询逻辑。

## 给已存在的子 agent 补一条消息

`agent_spawn` 返回值里有一个 `agent_key`（运行时实例句柄），用它（或你给的 `label`）就能后续追加消息：

```
agent_send agent_key="agent:reviewer:abc-123" message="顺便也看下 schema 变更"
```

要列当前活跃的子 agent：`agent_list`。

## 让 agent 自己写新的子 agent spec

`agent_generate` 工具（**默认关闭**）可以让 LLM 起草一份新的子 agent spec 并直接写到 `workspace/subagents/<name>.md`：

```java
// 开启方法（构建期）：
// 拿到 builder 内部的 SubagentsMiddleware 引用，调一下 enableAgentGenerateTool
```

适合"agent 跑到一半发现自己需要一类新的助手"。生产环境慎用——通常先让 agent 把方案写出来人工 review 再写文件。

## 一些行为细节

- **`description` 要写好**：这是模型决定要不要委派的关键依据。"代码评审"远不如"当用户要 review PR、找代码风格问题时使用"有效。
- **递归保护**：子 agent 不能再 spawn 子 agent（被强制标为"叶子"）；同时还有一个硬上限 3 层。
- **userId 透传**：父的 `RuntimeContext.userId` 会自动透到子，所以多租户隔离链不会断。
- **流式转发**：父 agent `stream()` 时，同步子 agent 的中间事件会实时流回父的 `Flux`（带来源标记），见下文 [子 Agent 流式](#子-agent-流式)。

## 远程子 agent

声明里只填 `url` + 可选 `headers`，子 agent 就走远程 HTTP 服务（Agent Protocol）执行：

```java
.subagent(SubagentDeclaration.builder()
    .name("remote-researcher")
    .description("远端调研子 agent")
    .url("http://agent-task-server:8080")
    .headers(Map.of("Authorization", "Bearer xxx"))
    .build())
```

同样支持同步（`timeout_seconds>0`）和后台（`timeout_seconds=0`）。

## 异步任务的存储位置

后台任务的状态默认写到 `workspace/agents/<parentAgentId>/tasks/<sessionId>.json`。这意味着：

- 在共享存储模式（多副本）下，任意节点都能读到任务状态；
- 任务执行**粘在创建节点**，但完成结果会被任意节点读到、并能正常推送回父 agent；
- 想取消可以从任意节点调 `task_cancel`——执行节点轮询取消标记后中止。

## 在 Plan Mode 下委派子 agent

⚠ 当前**已知缺口**：父 agent 在 Plan Mode 时 spawn 的子 agent **不会自动继承只读限制**。如果想限制子 agent，请在它的声明里用 `tools` 把工具列表收窄到只读工具，或者在子 agent 自己的 builder 里也开 `enablePlanMode()`。

## 子 Agent 流式

> 新代码请优先用 `streamEvents()`（返回 `Flux<io.agentscope.core.event.AgentEvent>`，与 Python 2.0 的 `agent.reply_stream()` 对齐的细粒度事件体系）。返回 `Flux<Event>` 的旧 `stream()` 系列在 2.0.0 起 `@Deprecated(forRemoval = true)` —— 详见 [消息与事件](../building-blocks/message-and-event.md) 与 [Changelog B.4](../change-log.md)。本节讲 `HarnessAgent` 在两套 API 下的子 agent 事件转发行为。

### 怎么选

| 场景 | 推荐 |
|------|------|
| 只关心父 agent 自身事件（文本增量、工具调用、生命周期） | **`streamEvents()`**（`Flux<AgentEvent>`） |
| **需要实时拿到子 agent 事件**（带 `EventSource` 的子流转发） | `stream()`（`Flux<Event>`）—— 目前唯一通道 |

`AgentEvent` 体系尚未提供与 `EventSource` 等价的子 agent 来源通道（在 v2 roadmap 上）。在通道落地前，需要实时子 agent 事件的调用方必须沿用已弃用的 `stream()`；只关心父事件的调用方今天就应该切到 `streamEvents()`。

### 父 agent 事件 —— `streamEvents()`（推荐）

```java
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;

parent.streamEvents(new UserMessage(message), ctx)
    .doOnNext(event -> {
        // event 是 io.agentscope.core.event.AgentEvent 的具体子类
        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
            System.out.print(((TextBlockDeltaEvent) event).getDelta());
        } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
            ToolCallStartEvent start = (ToolCallStartEvent) event;
            System.out.println("\n[tool] " + start.getToolName());
        }
        // 其他生命周期事件：AgentStartEvent / AgentEndEvent,
        // ModelCallStart/End、ToolResultStart/End、RequireUserConfirmEvent 等
    })
    .blockLast();
```

这条路径**不会**转发子 agent 事件 —— 通过 `agent_spawn` / `agent_send` spawn 出的子 agent 会静默运行完，最终结果以 `TOOL_RESULT` 块的形式回给父 agent。

### 子 agent 转发 —— `stream()`（已弃用，但目前唯一通道）

当你用 `parent.stream()` 调用主 agent，主 agent 在推理过程中又通过 `agent_spawn` / `agent_send` 调用子 agent，**子 agent 产生的所有中间事件会被实时注入到父的事件流里**。每个事件带一个 `EventSource` 字段，告诉你这个事件来自父还是哪个子 agent。

```
caller
  └─ parent.stream()                          ← @Deprecated(forRemoval=true)，但目前唯一
        │                                       能实时拿到子 agent 事件的入口
        ├─ parent 的 REASONING 块...          ← 父推理第一轮（含工具调用）
        │
        │  [agent_spawn "researcher" 开始]
        ├─ child 的 REASONING 块...           ← 子推理（实时转发，带 EventSource）
        ├─ child 的 TOOL_RESULT...
        ├─ child 的 AGENT_RESULT (last)       ← 子最终回复（实时转发）
        │  [agent_spawn 返回，子结果作为 TOOL_RESULT 传给父]
        │
        ├─ parent 的 TOOL_RESULT...
        ├─ parent 的 REASONING 块...          ← 父第二轮
        └─ parent 的 AGENT_RESULT (last)       ← 父最终回复
```

父 agent 自身事件 `source == null`；子 agent 事件 `source != null`。

#### 区分事件来源

```java
// 注意：stream(...) 已经是 @Deprecated(forRemoval=true)。这里保留只是因为它目前是
// 唯一能实时拿到子 agent 事件的 API。等 AgentEvent 上的子 agent 来源通道落地后，
// 请迁到 streamEvents(...)。
Flux<Event> events = parent.stream(msgs, StreamOptions.defaults(), ctx);

events.subscribe(event -> {
    EventSource src = event.getSource();
    if (src == null) {
        // 父 agent 自身
        System.out.printf("[parent][%s] %s%n",
                event.getType(), event.getMessage().getTextContent());
    } else {
        // 子（或孙）agent
        System.out.printf("[%s|depth=%d|path=%s][%s] %s%n",
                src.getAgentId(), src.getDepth(), src.getPath(),
                event.getType(), event.getMessage().getTextContent());
    }
});
```

`EventSource` 里常用的字段：

| 字段 | 含义 |
|------|------|
| `agentId` | 子 agent 的类型 id（`subagents/<id>.md` 的文件名） |
| `agentKey` | 运行时实例句柄，可以传给 `agent_send` |
| `agentName` | 显示名（可空） |
| `sessionId` | 子 agent 当次调用的会话 id |
| `parentSessionId` | 父 agent 的会话 id |
| `depth` | 嵌套深度（父直接子 = 1，孙 = 2，依此类推） |
| `path` | `/` 分隔的调用路径，多级嵌套自动叠加，如 `sess-001/planner/executor` |

### 多级嵌套（孙 agent）

子 agent 自己也可以 spawn 孙 agent（受 3 层硬上限保护）。孙 agent 的事件会逐级冒泡到父；按 `depth` 或 `path` 过滤即可定位任意层级：

```java
// 只取第一层子 agent 的 REASONING
events.filter(e -> e.getSource() != null
               && e.getSource().getDepth() == 1
               && e.getType() == EventType.REASONING)
      .subscribe(...);

// 只取路径包含 "executor" 的事件（任意深度）
events.filter(e -> e.getSource() != null
               && e.getSource().getPath().contains("executor"))
      .subscribe(...);
```

### SSE 转发

按客户端的需求挑 API：

**只转父 agent 事件**（大多数对话 UI 推荐这条）：

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String message,
                                          @RequestParam String sessionId) {
    RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
    return agent.streamEvents(new UserMessage(message), ctx)
            .map(event -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", event.getType().name());
                payload.put("id",   event.getId());
                if (event instanceof TextBlockDeltaEvent delta) {
                    payload.put("delta", delta.getDelta());
                } else if (event instanceof ToolCallStartEvent start) {
                    payload.put("toolName", start.getToolName());
                }
                return ServerSentEvent.<String>builder()
                        .data(objectMapper.writeValueAsString(payload))
                        .build();
            });
}
```

**需要把子 agent 事件也转给前端**（只能用已弃用的 `stream()`，直到 `AgentEvent` 子来源通道落地）：

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String message,
                                          @RequestParam String sessionId) {
    RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
    return agent.stream( // @Deprecated(forRemoval=true)，见上文说明
                    List.of(new UserMessage(message)),
                    StreamOptions.defaults(), ctx)
            .map(event -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", event.getType());
                payload.put("text", event.getMessage().getTextContent());
                payload.put("last", event.isLast());
                if (event.getSource() != null) {
                    payload.put("agentId", event.getSource().getAgentId());
                    payload.put("depth",   event.getSource().getDepth());
                    payload.put("path",    event.getSource().getPath());
                }
                return ServerSentEvent.<String>builder()
                        .data(objectMapper.writeValueAsString(payload))
                        .build();
            });
}
```

### 行为边界

| 场景 | 是否实时流转发？ |
|------|-----------------|
| `stream()`（已弃用） + 同步本地子 agent（`timeout_seconds > 0`） | ✔ |
| `streamEvents()`（推荐）—— 任意子 agent | ✗（仅父 agent 事件；`AgentEvent` 子来源通道是 roadmap 项） |
| `call()` 模式（非流式） | ✗（子结果以 `tool_result` 字符串返回） |
| `timeout_seconds = 0` 后台任务 | ✗（终态会通过反向通知给父 agent 下一轮） |
| 远程子 agent（Agent Protocol） | ✗ |
| 多级嵌套（孙 agent），`stream()` 路径 | ✔（自动叠 `path` / `depth`） |

### 错误处理

子 agent 内部出错时，框架会把错误捕获并写成一条 `TOOL_RESULT` 给父，**不会**把 `onError` 传播到父流——父流不会被子 agent 的失败打断。如果父流本身出错（比如模型调用失败），按标准 Reactor 语义处理（`onErrorResume` 等）。

## 相关文档

- [工作区](./workspace) — `subagents/` 与 `agents/<id>/tasks/` 的目录布局
- [计划模式](./plan-mode) — plan 阶段对子 agent 的限制
- [架构](./architecture) — 主/子 agent 怎么协作
- [消息与事件](../building-blocks/message-and-event.md) — `AgentEvent` 体系（推荐）以及已弃用的 `Event` / `EventType` / `StreamOptions`
- [Changelog B.4](../change-log.md) — `stream()` → `streamEvents()` 弃用时间线
