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
expose_to_user: true          # 可选三态；强制/禁止向用户暴露（不写表示不表态）
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

### 后台任务工具

子 agent 的生命周期背后由两组工具配合完成：

| 工具 | 职责 |
|------|------|
| `agent_spawn` | 创建子 agent，可选地执行任务（同步或后台） |
| `agent_send` | 向已存在的子 agent 追加消息 |
| `agent_list` | 列出当前活跃的子 agent 实例 |
| `task_output` | 通过 `task_id` 获取后台任务结果（阻塞或非阻塞） |
| `task_cancel` | 取消正在运行的后台任务 |
| `task_list` | 列出所有后台任务及其当前状态 |

`agent_spawn` / `agent_send` 管理子 agent **实例**（创建、复用、通信）；`task_output` / `task_cancel` / `task_list` 管理后台**任务结果**（查状态、取结果、取消）。两者的桥梁是 `task_id`——在 `agent_spawn` 或 `agent_send` 使用 `timeout_seconds=0` 时返回。

> 大多数情况下自动反向通知机制会把结果推回来，不需要显式调用任务工具。它们主要用作逃生口：在反向通知触发前主动检查进度、取消不再需要的任务、或者在对话压缩后恢复任务状态。

## 给已存在的子 agent 补一条消息

`agent_spawn` 返回值里有一个 `agent_key`（运行时实例句柄），用它或 `label` 就能后续追加消息：

```
agent_send agent_key="agent:reviewer:abc-123" message="顺便也看下 schema 变更"
```

如果 spawn 时设了 `label`，也可以用 label 来寻址：

```
agent_spawn agent_id="reviewer" task="review 这次 PR" label="pr-reviewer"
agent_send label="pr-reviewer" message="顺便也看下 schema 变更"
```

要列当前活跃的子 agent：`agent_list`。

## 持久会话

默认每次 `agent_spawn` 都创建新的子 agent 实例和会话——不保留之前调用的上下文。在声明里设 `persistSession(true)` 可以让同一子 agent 在多次 spawn 之间复用：

```java
.subagent(SubagentDeclaration.builder()
    .name("note-taker")
    .description("跨对话轮次积累笔记")
    .persistSession(true)
    .build())
```

开启后，框架会根据 `(parentSessionId, agentId, label)` 生成确定性的 key。如果再次 spawn 同样的组合，就会复用已存在的 agent 实例——对话历史和状态都保留。

## 向用户暴露子 Agent

通常子 agent 对用户是不可见的——它们在幕后作为父 agent 的内部工具运行。通过 `expose_to_user=true`，父 agent 可以把子 agent 暴露为**用户可直接交互的入口**：

```
agent_spawn agent_id="researcher" task="调研 AI 趋势" expose_to_user=true
```

这做了两件事：

1. **在 Gateway 里注册子 agent**，使其成为用户可寻址的入口
2. **发出一个 `SubagentExposedEvent`** 到流式事件流中，携带 `subagentId` 句柄

用户客户端收到 `SubagentExposedEvent` 后，就可以直接向子 agent 发消息——完全绕过父 agent：

```java
// 客户端：在事件流中监听暴露的子 agent
chat.sendStream(SendOptions.userId("user-1"), "派一个研究员调查 AI 趋势")
    .doOnNext(event -> {
        if (event instanceof SubagentExposedEvent se) {
            // se.getSubagentId() → 用来直接和子 agent 对话
            // se.getAgentId()    → 子 agent 类型（如 "researcher"）
            // se.getLabel()      → 可选的人类可读名称
        }
    })
    .blockLast();

// 直接向暴露的子 agent 发消息
chat.sendToSubagent(subagentId, "重点关注 LLM agent").block();
```

适合"分支对话"场景：父 agent spawn 一个专家，用户独立地和那个专家继续交流。完整的 Channel 侧 API 见 [Channel — 与暴露的子 Agent 对话](./channel#与暴露的子-agent-对话)。

### 怎么开启

用 `agent.channel(...)` —— bridge 自动接好，零配置：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("orchestrator")
    .model("dashscope:qwen-plus")
    .build();

// channel() 创建内部 gateway 并自动接好 bridge——expose_to_user 直接可用。
ChatUiChannel chat = agent.channel(ChatUiChannel.create());
```

没有绑定 Channel 时，`agent_spawn` 里的 `expose_to_user=true` 会被静默忽略——子 agent 照常工作，只是不会暴露给用户。多 agent 场景用 `GatewayBootstrap` 的接法见 [Channel — GatewayBootstrap 下暴露子 Agent](./channel#gatewaybootstrap-下暴露子-agent)。

### 用代码控制是否暴露

完全依赖 LLM 传 `expose_to_user=true` 有时不够灵活。你可以从应用代码侧覆盖这个决策，有两种方式，最终生效值按以下优先级解析（从高到低）：

1. **`RuntimeContext` 按调用覆盖** —— 作用于当前这次调用里的所有 `agent_spawn`
2. **`SubagentDeclaration` 按类型策略** —— 该子 agent 类型的静态默认值
3. **LLM 传入的 `expose_to_user` 工具参数**
4. 以上都没有表态时，默认为 **`false`**

**通过 `RuntimeContext` 按调用覆盖。** 在 `AgentSpawnTool.CTX_EXPOSE_TO_USER` 这个 key 下放一个 `Boolean`（或其字符串形式）：

```java
RuntimeContext ctx = RuntimeContext.builder()
    .userId("user-1")
    .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)   // 强制开启；传 false 则禁止暴露
    .build();
```

**通过声明设置按类型策略。** 使用三态的 `exposeToUser` —— `TRUE` 总是暴露，`FALSE` 永不暴露（即使 LLM 传了 `expose_to_user=true` 也会被覆盖），`null`（默认）则交给 context 覆盖、再交给 LLM 参数决定：

```java
SubagentDeclaration decl = SubagentDeclaration.builder()
    .name("researcher")
    .description("调研主题并返回汇总报告。")
    .exposeToUser(true)   // 这个子 agent 类型始终对用户可直接寻址
    .build();
```

或在 Markdown 子 agent spec 的 front matter 里（同样是三态——不写这个 key 表示"不表态"）：

```markdown
---
name: researcher
description: 调研主题并返回汇总报告。
expose_to_user: true
---
```

这样你就能不管模型怎么决定，都能强制或禁止暴露；同时在代码两侧都不表态时，仍然让 LLM 自行选择。

### 跨重启与多副本

默认情况下，暴露只存在于创建它的进程里：`subagentId` 只在那个节点有效，重启即失效。要让暴露的子 agent 在**任意副本**、**重启之后**都能解析，给 agent 配上 `distributedStore(...)` 即可——和配 state、filesystem 是同一行：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("orchestrator")
    .model("dashscope:qwen-plus")
    .distributedStore(RedisDistributedStore.fromJedis(jedis))
    .build();

ChatUiChannel chat = agent.channel(ChatUiChannel.create());  // 恢复能力自动接好
```

`subagentId` 会持久化到后端，子 agent 自己的对话会按 session 从分布式 `AgentStateStore` 重新加载——即使后续消息落到不同节点，用户面对的仍是*同一个*子 agent。多 agent 的 `GatewayBootstrap` 传 `.distributedStore(...)`（不传则继承 main agent 的）。部署建议——包括把某个 `subagentId` 路由回它的活实例所在节点（粘性路由）——见 [上生产](../others/going-to-production.md)。

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
- **权限继承**：父的所有 DENY 权限规则会自动传给子。如果父被禁用了某个工具，子也一样被禁——安全边界不会因为委派被绕过。在声明里设 `inheritParentPermissions(false)` 可以关闭这个行为。
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

父 agent 在 Plan Mode 时 spawn 的子 agent 会**自动继承只读限制**——子 agent 在 spawn 时就会被置入 Plan Mode，无法执行写操作，安全边界在委派链上不会断。

## 子 Agent 流式

> 新代码请用 `streamEvents()`（返回 `Flux<AgentEvent>`）。旧 `stream()` 系列（`Flux<Event>`）在 2.0.0 起 `@Deprecated(forRemoval = true)` —— 详见 [消息与事件](../building-blocks/message-and-event.md) 与 [V1 迁移指南 B.4](../change-log.md)。

父 agent 通过 `agent_spawn` / `agent_send` 同步调用子 agent 时，子 agent 的中间事件会**实时转发**到父的 `streamEvents()` 流中。每个子事件都带一个 `source` 字段（`/` 分隔的路径，如 `"main/researcher"`），父事件的 `source` 为 `null`。

```
caller
  └─ parent.streamEvents(msg, ctx)
        │
        ├─ AGENT_START                            ← 父 agent 启动
        ├─ TEXT_BLOCK_DELTA …                     ← 父推理
        ├─ TOOL_CALL_START "agent_spawn"
        │
        │  [子 agent 创建]
        ├─ AGENT_START          (source="main/researcher")  ← 子启动
        ├─ TEXT_BLOCK_DELTA …   (source="main/researcher")  ← 子推理
        ├─ TOOL_CALL_START …    (source="main/researcher")
        ├─ TOOL_RESULT_END …   (source="main/researcher")
        ├─ AGENT_END            (source="main/researcher")  ← 子结束
        │  [agent_spawn 返回，子结果作为 TOOL_RESULT 传给父]
        │
        ├─ TOOL_RESULT_END                        ← 父收到工具结果
        ├─ TEXT_BLOCK_DELTA …                     ← 父第二轮推理
        └─ AGENT_END                              ← 父结束
```

### 使用 `streamEvents()`（推荐）

```java
parent.streamEvents(new UserMessage(message), ctx)
    .doOnNext(event -> {
        String src = event.getSource();
        String prefix = (src != null) ? "[" + src + "] " : "";

        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
            System.out.print(prefix + ((TextBlockDeltaEvent) event).getDelta());
        } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
            System.out.println(prefix + "[tool] " + ((ToolCallStartEvent) event).getToolCallName());
        } else if (event.getType() == AgentEventType.AGENT_START) {
            if (src != null) System.out.println("── 子 agent 启动: " + src);
        } else if (event.getType() == AgentEventType.AGENT_END) {
            if (src != null) System.out.println("── 子 agent 结束: " + src);
        }
    })
    .blockLast();
```

区分父子事件：

```java
// 只看父事件
events.filter(e -> e.getSource() == null).subscribe(…);

// 只看子事件
events.filter(e -> e.getSource() != null).subscribe(…);

// 只看特定子 agent 的事件
events.filter(e -> e.getSource() != null && e.getSource().contains("researcher")).subscribe(…);
```

### SSE 转发

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
                if (event.getSource() != null) {
                    payload.put("source", event.getSource());
                }
                if (event instanceof TextBlockDeltaEvent delta) {
                    payload.put("delta", delta.getDelta());
                } else if (event instanceof ToolCallStartEvent start) {
                    payload.put("toolName", start.getToolCallName());
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
| `streamEvents()` + 同步本地子 agent（`timeout_seconds > 0`） | ✔ |
| `call()` 模式（非流式） | ✗（子结果以 `tool_result` 字符串返回） |
| `timeout_seconds = 0` 后台任务 | ✗（终态会通过反向通知给父 agent 下一轮） |
| 远程子 agent（Agent Protocol） | ✗ |

### 错误处理

子 agent 内部出错时，框架会把错误捕获并写成一条 `TOOL_RESULT` 给父，**不会**把 `onError` 传播到父流——父流不会被子 agent 的失败打断。如果父流本身出错（比如模型调用失败），按标准 Reactor 语义处理（`onErrorResume` 等）。

## 相关文档

- [Channel](./channel) — `expose_to_user`、`SendOptions`、用户直接与子 agent 交互
- [工作区](./workspace) — `subagents/` 与 `agents/<id>/tasks/` 的目录布局
- [计划模式](./plan-mode) — plan 阶段对子 agent 的限制
- [架构](./architecture) — 主/子 agent 怎么协作
- [消息与事件](../building-blocks/message-and-event.md) — `AgentEvent` 体系（推荐）以及已弃用的 `Event` / `EventType` / `StreamOptions`
- [V1 迁移指南 B.4](../change-log.md) — `stream()` → `streamEvents()` 弃用时间线
