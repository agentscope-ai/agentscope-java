# 子 Agent（Subagent）

## 作用

Subagent 让主 agent 把「可独立处理、上下文重、可并行」的任务委派出去，避免主线程膨胀。  
每个 subagent 都是一个临时 `HarnessAgent`（或 remote stub）实例，拥有独立子会话，最终通过工具结果回传。

---

## 何时启用

当 `HarnessAgent.build()` 满足以下条件时，才会装载 subagent 能力：

- 当前 agent **不是** leaf subagent
- 没有 `disableSubagents()`
- 已配置 `model`

满足后会注册 `SubagentsHook`（priority=80），并通过 hook 暴露：

- `agent_spawn` / `agent_send` / `agent_list`
- `task_output` / `task_cancel` / `task_list`

在每轮 `PreReasoningEvent`，`SubagentsHook` 会向 SYSTEM 注入：

- 子 agent 使用规则
- 当前可用 `agent_id` 列表
- 当前 session 的异步任务摘要（最多 10 条）

---

## 声明来源

`buildSubagentEntries(...)` 会合并四类来源：

1. 内置 `general-purpose`
2. 编程声明：`builder.subagent(SubagentDeclaration)`
3. 文件声明：`workspace/subagents/*.md`（`AgentSpecLoader` 非递归加载）
4. 自定义工厂：`builder.subagentFactory(name, factory)`

---

## 声明模型（SubagentDeclaration）

`SubagentDeclaration` 支持 3 种互斥来源模式：

1. **Definition workspace 模式**
   - `workspace(path)` 指向定义目录（通常含 `AGENTS.md`）
2. **Inline 模式**
   - `inlineAgentsBody(...)` 直接作为系统提示词 base
3. **Remote HTTP 模式**
   - `url(...)` + 可选 `headers(...)`，通过 task protocol 走远端执行

互斥约束（`build()` 校验）：

- `url` 不能与 `workspace` 或非空 `inlineAgentsBody` 同时出现
- `workspace` 与非空 `inlineAgentsBody` 不能同时出现

---

## 运行时工作区五行判定表

`WorkspaceMode` 决定 runtime workspace root：

| 情形 | sysPrompt base 来源 | runtime workspace |
|---|---|---|
| 内置 `general-purpose`（固定共享） | 无额外 base（只拼 Subagent Context；`AGENTS.md` 仍会由 WorkspaceContextHook 注入） | `mainWorkspace` |
| `workspace.path` + `ISOLATED` | `<workspace.path>/AGENTS.md`（无则空） | `workspace.path` |
| `workspace.path` + `SHARED` | `<workspace.path>/AGENTS.md`（无则空） | `mainWorkspace` |
| 无 `workspace.path` + `ISOLATED`（默认） | `inlineAgentsBody` / markdown body | `mainWorkspace/agents/<name>/workspace`（自动创建） |
| 无 `workspace.path` + `SHARED` | `inlineAgentsBody` / markdown body | `mainWorkspace` |

补充：

- `tools` 是**继承工具的 allowlist**：仅过滤父 toolkit，不影响子 agent 后续自动注册的本地工具
- 同一个 definition workspace 可被多个声明复用
- `workspace.path` 相对路径会按 `mainWorkspace.resolve(...).normalize()` 解析

---

## 声明文件（`workspace/subagents/<id>.md`）

文件名（去掉 `.md`）就是 `agent_id`，不从 front matter 读取 `name`。

```markdown
---
description: 代码评审专家
workspace:
  mode: isolated               # isolated | shared，默认 isolated
  path: ./defs/reviewer        # 可选；相对 mainWorkspace 或绝对路径
model: openai:gpt-4o-mini      # 可选
maxIters: 8                    # 可选，默认 10
tools: [read_file, grep_files] # 可选
---

你是一个专注代码评审的子 agent。
```

解析规则（`AgentSpecLoader`）：

- 必填：`description`
- 仅扫描 `subagents/` 目录**第一层** `.md` 文件（非递归）
- 如果配置了 `workspace.path` 且 body 非空：会记录 warning，body 被忽略
- markdown 声明当前不解析 `url/headers`（remote 声明建议走编程 API）

---

## 编程式配置

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
        .maxIters(8)
        .tools(List.of("read_file", "grep_files"))
        .build())
    .subagent(SubagentDeclaration.builder()
        .name("remote-researcher")
        .description("远端调研子 agent")
        .url("http://agent-task-server:8080")
        .headers(Map.of("Authorization", "Bearer xxx"))
        .build())
    .build();
```

---

## 内置 `general-purpose`

内置 `general-purpose` 不需要写声明文件，会始终加入 entry 列表。  
它的目标是「能力镜像主 agent」，核心行为：

- 共享主 workspace（`SHARED` 语义）
- 继承并镜像主 agent 的：
  - toolkit（父工具）
  - hooks
  - execution config
  - compaction / toolResultEviction
  - additional context files / maxContextTokens
  - 各类 disable 开关
- 固定是 leaf subagent（不能继续 spawn）

---

## 防递归与深度保护

双保险：

1. 所有通过声明/内置生成的子 agent 都会 `asLeafSubagent()`，leaf 不再注册 `SubagentsHook`
2. `AgentSpawnTool` 还有动态深度上限 `MAX_SPAWN_DEPTH = 3`

---

## RuntimeContext 透传

`agent_spawn` / `agent_send` 调用子 agent 时：

- 子会话 `session_id` 为新值（`sub-<uuid>`）
- `userId` 从父 `RuntimeContext` 透传给子 `RuntimeContext`

这样可保持 USER 维度隔离键一致（例如 namespace/sandbox 隔离依赖 userId 的场景）。

---

## 调用工具与关键参数

| 工具 | 作用 | 关键参数 |
|---|---|---|
| `agent_spawn` | 生成子 agent，可同步/异步执行首条任务 | `agent_id` 必填；`task` 可选；`label` 可选；`timeout_seconds` 默认 30，`0`=后台，最大 600 |
| `agent_send` | 给已有子 agent 发后续消息 | `agent_key` 或 `label` 二选一；`message` 必填；`timeout_seconds` 规则同上 |
| `agent_list` | 列当前活跃子 agent | 无 |
| `task_output` | 查询/等待后台任务结果 | `task_id`、`block`（默认 true，建议查状态用 false）、`timeout` 默认 30000ms，最大 600000ms |
| `task_cancel` | 取消任务 | `task_id` |
| `task_list` | 列当前 session 任务 | `status_filter`（running/completed/failed/cancelled/all） |

注意：

- `agent_send` 的 `agent_key` 必须使用 `agent_spawn` 返回值中的完整 `agent_key: ...`（不是 `agent_id` / `session_id` / `task_id`）
- 异步任务刚创建时不要立即轮询；优先先返回用户，再用 `task_output(block=false)` 或 `task_list` 查最新状态

---

## 异步任务生命周期与存储

默认情况下，主 agent 使用 `WorkspaceTaskRepository`（除非显式 `taskRepository(...)` 覆盖）。

生命周期（简化）：

1. `putTask(...)` 写入 `TaskRecord(PENDING)` 到 workspace
2. 提交本地执行 future（local 或 remote）
3. 执行中更新为 `RUNNING`
4. 结束写入 `COMPLETED / FAILED / CANCELLED`

存储分层：

- 内存层：`localTasks`（本节点加速句柄，重启丢失）
- 持久层：`agents/<parentAgentId>/tasks/<sessionId>.json`（状态真源）

---

## 分布式语义

- 任务执行粘在创建节点，但任意节点都可通过 workspace 读取状态
- `task_output(block=true)` 在跨节点场景下会优雅降级，不会无休止阻塞
- `task_cancel` 会把 `cancelRequested=true` 持久化；执行节点轮询该标记后中止
- orphan sweeper 会把长时间无心跳的本地任务标记为 `FAILED`（remote transport 任务不走该判定）

与 filesystem 模式关系：

| 模式 | 任务路径 `agents/<agentId>/tasks/` 可见性 |
|---|---|
| `RemoteFilesystemSpec` | 路由到共享远端存储，多节点可见 |
| `SandboxFilesystemSpec` | 走沙箱文件系统与沙箱状态持久化 |
| `LocalFilesystemSpec` / 默认本地 | 本机本地可见 |

---

## Remote subagent 行为

当声明配置 `url(...)` 时：

- 工厂返回 `RemoteSubagentStub`（占位，不做本地真实推理）
- 实际执行通过 `TaskRunSpec.RemoteTaskRunSpec` + `AgentProtocolTaskClient` 委派到远端 task HTTP 服务
- 可同步（`timeout_seconds>0`）或异步（`timeout_seconds=0`）

---

## 实践建议

1. `description` 要写清「何时使用 / 输出格式 / 禁止事项」，这是主模型是否委派的关键依据
2. 子 agent `maxIters` 通常设得比主 agent 小，避免子线程吞噬过多 token
3. 会话压缩或恢复后，先用 `task_list()` 恢复任务全量状态，再做单任务查询

---

## 相关文档

- [工具](./tool.md)
- [工作区](./workspace.md)
- [架构](./architecture.md)
- [流式输出](./streaming.md)
