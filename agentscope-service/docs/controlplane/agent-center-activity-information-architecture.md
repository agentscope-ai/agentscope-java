# Agent Center Activity 信息架构

## 决策

控制面继续保留 `Issue -> OrchestrationRun -> RunNode -> AgentTask -> ExecutionAttempt`
完整领域模型，但产品界面不再提供独立的 Operations 区域。

跨 Agent 的执行与会话统一放入 **Agent Center / Activity**：

- Executions 以 OrchestrationRun 为一条记录，展开后依次查看 AgentTask 和 ExecutionAttempt；
- Sessions 表达对话上下文连续性，不与 Execution 合并为同一个领域对象；
- Agent 详情展示该 Agent 的 Sessions、相关工作以及 External Application 实例；
- Runtime Host、Runtime Profile、Runtime Pool 和 Runtime Policy 作为内部调度资源保留，不提供独立页面。

## 概念与受众

| 概念 | 语义 | 默认受众 |
| --- | --- | --- |
| Issue | 长期工作、讨论和验收事实源 | Work Hub 用户 |
| Execution / Run | 一轮编排、控制和资源统计 | Operator / Admin |
| Session | Agent 对话与上下文连续性 | Agent 构建者与运维人员 |
| Agent step / AgentTask | 面向一个 Agent 的持久执行义务 | Team 构建者与运维人员 |
| Runtime attempt / ExecutionAttempt | 某个 backend 上的一次物理尝试 | 运维与排障人员 |

全局 Activity 继续使用内部 `operations` 权限能力，仅 Operator 和 Admin 可访问。Agent 详情中的
Agent-scoped 投影继续遵循 Agent Center 权限。Agent 开发者可以从 Agent 详情进入属于该 Agent 的
只读 Session detail；后端会校验 Session 的稳定 `agentId` 归属，不能借 query 参数读取其他 Agent。
产品导航与后端授权能力不要求一一对应。

## 路由

规范路径为：

```text
/agent-center/activity/executions
/agent-center/activity/executions/:runId
/agent-center/activity/tasks/:taskId
/agent-center/activity/sessions
/agent-center/activity/sessions/:sessionId
/agent-center/agents/:agentId/sessions/:sessionId
```

最后一条是从 Agent 详情进入的 Agent-scoped 只读视图。旧 `/operations/**` 和相关
`/control/**` 路径只作为兼容重定向保留。

## 运行约束

- 基础设施重试只能在同一 AgentTask 下创建新 ExecutionAttempt；
- 节点策略或业务重试创建新 AgentTask；
- 活跃 Run 内的委派和后续工作复用 Run，终态 Run 的人工 rerun 创建新 Run；
- Endpoint 调用者以 `invocationId` 为公共身份，Issue、Run、Task 和 Attempt 只作为关联与诊断信息。

## 后续演进

- 为 Executions API 增加服务端聚合、分页以及状态、来源和 backend 筛选；
- 将 Runtime Policy 的常用并发、超时字段投影为 Agent 高级设置；
- 如果未来确认不需要多候选和 fallback，再评估把 Runtime Policy 折叠进 AgentBinding。
