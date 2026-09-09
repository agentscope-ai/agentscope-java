# 核心概念

先区分“谁可以操作”“使用什么资源”和“在哪里执行”，再理解任务与执行记录的关系。

| 对象 | 作用 |
| --- | --- |
| Namespace | 资源归属与成员授权范围 |
| Agent | 可复用的身份、指令、模型、工具及运行配置 |
| Workspace | 文件、技能和 Agent 工作资料；不是 Namespace 的别名 |
| Environment | 托管 Agent 的工具执行环境，如 Local、Sandbox、Self-hosted |
| Runtime Host | 安装 provider 并执行 Hosted Agent 的主机守护进程 |
| Session | 一段持续会话及其事件历史 |
| Issue | 工作目标、讨论、交付物和验收状态 |
| Team | 包含 leader、成员及协作策略的 Agent 集合 |
| AgentTask | 一项定向的执行义务 |
| ExecutionAttempt | 一次具体尝试；重试可能产生新的 Attempt |
| Run | 编排执行及其节点关系 |

## 一项工作如何流转

```{mermaid}
flowchart LR
    A[人或外部事件] --> B[Issue / Session]
    B --> C[AgentTask / 执行请求]
    C --> D[Managed 或外部 Runtime]
    D --> E[事件与交付物]
    E --> B
```

Session 空闲、某次 Attempt 成功和 Issue 通过验收分别描述不同层次。看到 Agent 回复后，还应检查交付物和 Issue 的评审状态。

选择自己的使用路径：[托管 Agent](agents.md)、[Runtime Host](runtime-host.md) 或[应用接入](integrations.md)。
