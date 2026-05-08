# 子 Agent（Subagent）

## 作用

让父 agent 能把“独立、重上下文、可并行”的子任务交出去，不打扰主线。子 agent 是**临时**的 `HarnessAgent` 实例：独立 sysPrompt、独立 Memory、不共享父对话历史，仅返回一条结果作为 `tool_result`；同时支持同步 / 异步两种调用。

## 触发

| 时机 | 动作 |
|------|------|
| `HarnessAgent.build()` | 非 leaf 且有 model 时注册 `SubagentsHook`（priority 80）与 `AgentSpawnTool` / `TaskTool` |
| `PreReasoningEvent` | `SubagentsHook` 拼的“Subagents”指南段 + 所有可用 agent_id 注入第一条 SYSTEM 消息 |
| reasoning 选中子 agent 工具 | `agent_spawn` / `agent_send` / `agent_list` 走同步路径；`timeout_seconds=0` 走异步，返 `task_id` |
| 后续轮次 | `task_output` / `task_cancel` / `task_list` 调 `TaskRepository` 拿结果、取消、查看 |

> 在 session mode（`AgentBootstrap` 下 `externalSubagentTool != null`）中，上面三个 `agent_*` 工具会被重命名为 `sessions_spawn` / `sessions_send` / `sessions_list`。

## 关键逻辑

### Spec 来源与汇集

```mermaid
flowchart LR
    Built[内置 general-purpose<br/>镜像父配置 + asLeafSubagent] --> Entries[buildSubagentEntries]
    Spec[编程 SubagentSpec<br/>builder.subagent ] --> Entries
    MD[workspace/subagents/*.md<br/>AgentSpecLoader] --> Entries
    Custom[builder.subagentFactory<br/>name to Function] --> Entries
    Entries --> Hook[SubagentsHook]
    Hook --> ToolMain{工具集}
    ToolMain -->|tools()| Spawn[AgentSpawnTool]
    ToolMain -->|tools()| TaskT[TaskTool]
```

- **内置 `general-purpose`**：镜像主 agent 的 model / workspace / hooks / skills 等配置，调用 `asLeafSubagent()` 禁用递归，适合任意可委派的子任务。
- **编程 `SubagentSpec`**：`builder.subagent(spec)` 一个个加。
- **`workspace/subagents/*.md`**：`AgentSpecLoader.loadFromDirectory` 递归扫，解析 YAML front matter + Markdown body。
- **自定义工厂**：`builder.subagentFactory(name, Function<String, Agent>)`，完全控制构建逻辑。

### Spec 的两种描述形式

**Markdown front matter**（`workspace/subagents/research.md`）——推荐：

```markdown
---
name: research-analyst
description: 调研主题、查找文档、汇总外部信息。
model: qwen3-max
maxIters: 15
tools: read_file, grep_files
---

你是一名研究分析师。输出带引用、不确定处要明说。
```

`AgentSpecLoader.parse` 实际仅读 `name` / `description` / `tools`（逗号分）/ `model` / `maxIters`；body 作为 `sysPrompt`。 
`SubagentSpec` 还有 `workspace` 字段，但当前只在**编程式**（手动 `setWorkspace`）生效，Markdown 里写不会被读。

**编程**：

```java
SubagentSpec spec = new SubagentSpec("data-analyst", "SQL / 数据聚合 / 趋势");
spec.setSysPrompt("你是数据分析专家...");
spec.setMaxIters(10);

HarnessAgent.builder()
    .name("Orchestrator").model(model).workspace(workspace)
    .subagent(spec)
    .build();
```

### 防递归 + 防超深

- `SubagentSpec` 生成的子 agent 都调了 `Builder.asLeafSubagent()`：`leafSubagent=true` 时 `build()` **不注册** `SubagentsHook`，因此子 agent 看不到这些工具，无法再 spawn。
- 在 `AgentSpawnTool` 里还额外限了一道防线：`MAX_SPAWN_DEPTH = 3`，作为动态保险。

### 调用语义

| 工具 | 作用 | 关键参数 |
|------|------|---------|
| `agent_spawn` | 生成一个子 agent 跑一件任务 | `agent_id`（必填）、`task`（可选，留空则只建 session 不跑）、`label`（可选别名）、`timeout_seconds` 默认 30s，`0` 走后台，上限 600s |
| `agent_send` | 给已存在的子 agent 补一条话 | `agent_key` （spawn 返回的句柄，不是 `agent_id`/`task_id`）或 `label`；`message`；`timeout_seconds` |
| `agent_list` | 列当前活跃子 agent | 无 |
| `task_output` | 取后台任务结果 | `task_id`、`block`（默认 true）、`timeout` 默认 30s，上限 600s |
| `task_cancel` | 取消任务 | `task_id` |
| `task_list` | 列任务，可按状态过滤 | `status_filter`：running / completed / failed / cancelled |

### TaskRepository 与 BackgroundTask

- 默认 `DefaultTaskRepository` 是进程内 `ConcurrentHashMap` + cached daemon thread pool。要跨进程（如 Redis / DB）只需实现 `TaskRepository` 接口 并 `builder.taskRepository(...)`。
- `BackgroundTask` 包装 `CompletableFuture<String>`，记录 `taskId / agentId / createdAt / lastCheckedAt`。
- `TaskStatus`：`PENDING` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED`，`isTerminal()` 返回后三者。

## 配置示例

```java
HarnessAgent orchestrator = HarnessAgent.builder()
    .name("orchestrator").model(model).workspace(workspace)
    .subagent(researchSpec)                                  // (1) 编程
    .subagentFactory("my-specialist", id ->                  // (2) 自定义工厂
        HarnessAgent.builder().name(id).model(specialModel)
            .workspace(Path.of("./specialist-workspace"))
            .toolkit(customToolkit).build())
    .taskRepository(new RedisTaskRepository(...))            // (可选)
    .build();
// (3) workspace/subagents/*.md 会被自动扫描
// (4) 内置 general-purpose 总是在位
```

编排 prompt 中子 agent 如何被选中完全依赖 `description`，尽量明确“何时用 / 何时不用 / 输出形式”。同时 `maxIters` 宜比父 agent 小，避免子任务贪吃 token。

## 相关文档

- [工具](./tool.md) — `agent_spawn` / `agent_send` / `agent_list` / `task_*` 的完整参数表
- [工作区](./workspace.md) — `workspace/subagents/` 与自动发现
- [架构](./architecture.md) — SubagentsHook 在生命周期中的位置与同步 / 后台两条委派路径的时序图
