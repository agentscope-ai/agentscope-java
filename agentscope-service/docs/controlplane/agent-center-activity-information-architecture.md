# 统一控制台信息架构

## 决策

控制面继续保留 `Issue -> OrchestrationRun -> RunNode -> AgentTask -> ExecutionAttempt`
完整领域模型，以及独立的 Session 上下文模型；领域对象不因为导航简化而合并。

侧边栏只保留三类入口：

- **Work**：Chat、Issues、Approvals、Automations；
- **Design**：Agents、Teams、Workflows、Channels；
- **Resources**：Workspaces、Environments、Memory、Vault。

不再提供独立的 Observe 分组，也不在命令面板中列出全局 Activity、Executions 和 Sessions。
这些页面保留为可深链的二级诊断视图，由用户正在查看的业务或设计对象带入上下文：

| 上下文详情 | 主要关联视图 |
| --- | --- |
| Agent | Sessions、Activity（AgentTask、来源 Issue 和 Execution） |
| Team | Activity（该 Team 冻结快照创建的 AgentTask 和 Execution） |
| Workflow | 使用该 Workflow revision 的 Execution |
| Issue | 内联 Activity、Execution 和 Artifact |
| Published API | Invocation 关联的 Issue、Execution 或 Session |
| Chat | 当前会话的 Session diagnostics |

Capabilities 不再作为 Agent 详情的独立页签。运行时能力仍属于调度、命令可用性和兼容性判断的
内部事实；只有当某项能力能转化为用户可理解、可操作的功能时，才在对应 Session、Runtime 或设置
界面中按需展示。

## 概念与受众

| 概念 | 语义 | 默认受众 |
| --- | --- | --- |
| Issue | 长期工作、讨论和验收事实源 | 协作用户 |
| Execution / Run | 一轮编排、控制和资源统计 | Operator / Admin |
| Session | Agent 对话与上下文连续性 | Agent 构建者与运维人员 |
| Agent step / AgentTask | 面向一个 Agent 的持久执行义务 | Team 构建者与运维人员 |
| Runtime attempt / ExecutionAttempt | 某个 backend 上的一次物理尝试 | 运维与排障人员 |
| Activity | 某个业务对象的事件投影，不是新的顶层领域对象 | 该业务对象的使用者 |

全局 Executions 与 Sessions 继续使用内部 `operations` 权限能力，仅 Operator 和 Admin 可访问，
但不再作为默认落地页或主导航入口。Agent 详情中的 Agent-scoped 投影继续遵循 Agent 开发权限。
Agent 开发者可以从 Agent 详情进入属于该 Agent 的只读 Session detail；后端会校验 Session 的
稳定 `agentId` 归属，不能借 query 参数读取其他 Agent。产品导航与后端授权能力不要求一一对应。

## 路由

规范路径保持为：

```text
/work/executions
/work/executions/:runId
/work/executions/tasks/:taskId
/work/sessions
/work/sessions/:sessionId
/agent-center/agents/:agentId/sessions/:sessionId
```

这些路径是稳定的诊断地址，不代表必须存在同名一级菜单。最后一条是从 Agent 详情进入的
Agent-scoped 只读视图。URL 中保留 `/work` 和 `/agent-center` 仅作为稳定的技术命名空间。
旧 `/agent-center/activity/**`、`/operations/**` 和相关 `/control/**` 路径只作为兼容重定向保留。

## Channel、Agent 与 GitHub webhook

Channel 是一个全局可管理的**外部接入连接**，拥有自己的 provider 类型、凭证、回调地址、启停状态、
会话隔离策略和路由规则。它不是 Agent 的运行时能力，也不是某个 Agent 进程的附属配置。因此：

1. Channel 放入 Design 分组，和 Agent、Team、Workflow 并列；单独的 Connect 分组没有必要。
2. Channel 可以先完成连接配置再绑定目标，也可以把同一个连接中的不同 peer、group、repository 或
   account 路由到不同目标，所以仍需保留全局列表和详情页。
3. Channel binding 只能引用稳定的逻辑对象。当前实现的 `defaultAgentId` 和路由规则绑定逻辑 Agent；
   **绝不绑定 `AgentInstance`**，因为实例是会重启、扩缩容和换代的临时进程。
4. Agent 详情只展示和编辑指向该 Agent 的 Channel 投影，作为常用快捷入口；凭证、全局启停和跨目标
   路由仍回到 Channel 详情管理。
5. 如果未来让 Channel 直接触发 Team 或 Workflow，binding 应演进为显式的
   `(targetType, targetRef)`，而不是复用 Agent ID 字段或绑定某个成员实例。

GitHub webhook 需要按业务语义拆分，不能因为传输方式相同就全部归入 Channel：

- GitHub Issue / PR 作为外部事实源并双向同步标题、状态和评论时，属于 **Work Source**，投影到
  AgentScope Issue，再由 Issue assignment 决定 Agent、Team 或 Workflow；
- GitHub comment 或事件被当作一条即时消息直接送入 Agent Session 时，可以使用 **Channel**；
- GitHub 事件触发一次有审计、可重试的后台工作时，应进入 Automation / Issue / Execution 链路，
  而不是把 webhook delivery 本身伪装成 Session。

这样可以同时保留通用 Channel 接入和 GitHub 原生协作，而不会把“外部工作事实源”与“消息路由器”
混成一个概念。

## 运行约束

- 基础设施重试只能在同一 AgentTask 下创建新 ExecutionAttempt；
- 节点策略或业务重试创建新 AgentTask；
- 活跃 Run 内的委派和后续工作复用 Run，终态 Run 的人工 rerun 创建新 Run；
- Endpoint 调用者以 `invocationId` 为公共身份，Issue、Run、Task 和 Attempt 只作为关联与诊断信息；
- Session Event 是 append-only 的完整运行日志。Chat 的 Events 视图直接读取该日志；Operator 还能
  从 Chat 进入 Session diagnostics，查看历史分页、实时流、原始 payload、Context、Turn、Task 和 Command。

## 后续演进

- 为详情页提供服务端按 Agent、Team、Workflow、Issue 查询 Execution / Session / Activity 的投影 API，
  避免前端先取全局集合再筛选；
- 将旧 Channel owner 范围统一到 v5 tenant / namespace 授权边界，并把 agent-scoped presence 明确为投影；
- 为 Channel binding 引入 typed target，为 Team / Workflow 路由预留兼容演进路径；
- 将 GitHub Work Source 的配置、同步状态和 webhook delivery diagnostics 放在 Issue/Work Source 上下文中；
- 将 Runtime Policy 的常用并发、超时字段投影为 Agent 高级设置；
- 如果未来确认不需要多候选和 fallback，再评估把 Runtime Policy 折叠进 AgentBinding。
