---
title: "External 支持的框架与自定义适配"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

External 的支持范围由 SDK 适配器及其所接入对象共同决定。这里列出仓库已有的适配路径，方便选择集成对象和验收范围。

## Java

`agentscope-extensions-aistio` 提供 AgentScope Java `Agent` 的适配器，包含上下文、消息、会话命令、中止与任务查询等合约能力。不同 Agent 的底层实现仍决定具体命令能否执行。

可选扩展点：`SessionHistorySource` 提供历史，`AgentRuntimeSource` 提供 Workspace/Subagent 目录与运行信息，`AgentTaskStarter` 启动派发任务。`HarnessAgentTaskStarter` 可以结合 Workspace 工厂消费平台定义。只有配置任务 starter 才声明 `agent-task`，不能仅创建 bridge 就视为工作执行器。

## Python 内置适配器

| 框架 | 典型接入对象/方式 | 已实现的适配重点 |
| --- | --- | --- |
| AgentScope | Agent 实例与 hooks | 上下文、消息、命令、中止、任务查询 |
| OpenAI Agents SDK | Session 或含 Session 的对象 | 观察 Session items、上下文与消息；命令依赖后端方法 |
| LangChain / LangGraph | 对应框架对象与 callbacks/state | 模型/工具事件、上下文与消息 |
| Google ADK | SessionService 等框架对象 | Session 事件、上下文、消息与对应命令 |
| Claude Agent SDK | Client / Session store 对象 | 会话存储、上下文、消息与对应命令 |
| OpenClaw | Gateway RPC 客户端/连接入口 | 上下文、消息、Subagent 与 Workspace 目录 |

自动识别使用 `can_handle(target)`，并按注册顺序选择首个匹配项。遇到包装对象无法识别或多个适配器可能匹配时，通过 `adapter=` 显式指定目标适配器。

**当前 Python 内置适配器没有实现 `handle_agent_task`。** 它们可以接入观测与各自实现的会话能力，但不自动获得 Issue/Team 派发能力。框架名相同也不代表 External 与 Hosted 的能力相同，例如 Claude Agent SDK 集成与 Runtime Host 启动 Claude Code CLI 是两条路径。

## 自定义适配器实现哪些部分

| 扩展点 | 应提供的行为 |
| --- | --- |
| `can_handle` | 判断对象类型，不启动任务 |
| `attach` / `detach` | 挂载、移除框架 hook 或观察器 |
| `extract_context` | 提取会话上下文 |
| `list_messages` | 返回可读取的历史消息 |
| `handle_command` / `abort` | 执行真实命令与取消，返回真实失败 |
| `handle_agent_task` | 为一个派发建立隔离执行，正确处理结果、失败与取消 |

继承 `FrameworkAdapter` 后通过 `aistio.instrument(..., adapter=your_adapter)` 使用，或用 `register_adapter()` 加入注册表。基础类会根据覆写方法推导能力；不要用空实现换取 capability 标志。

## 任务接入的额外验收

先验证两个并行 Attempt 不共享会话状态，再验证重试、取消、事件恢复和 Artifact 上传。只显示最终 assistant 消息不等于完成 Attempt；团队协调角色还必须处理节点完成与失败。应用自行维护所需模型、工具和部署依赖。

相关：[连接参数](/v2/zh/service/external-agent-configuration) · [工作原理](/v2/zh/service/external-agent-execution) · [Team 协作](/v2/zh/service/team-collaboration)。
