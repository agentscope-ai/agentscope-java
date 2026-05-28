---
分析结论

1. workspaces 目录

来源: BuilderWorkspaceConfig + WorkspaceManagerFactory (仅用于 builder 的多租户 Web 场景)

.agentscope/workspaces/users/{userId}/agents/{agentId}/

这是 builder Web 端的多租户文件系统。BuilderWorkspaceConfig 在 buildLocalFilesystem() 中创建 .agentscope/workspaces 作为 LocalFilesystem 的 root，然后 WorkspaceManagerFactory.forAgent(ownerId,
agentId) 通过 NamespacedFilesystemView 将路径路由到 users/{ownerId}/agents/{agentId}。

跟 HarnessAgent 底层无关。HarnessAgent 自身的 workspace 默认是 .agentscope/workspace（注意没有 s）。workspaces 仅服务于 builder 前端的多租户隔离层——给每个登录用户的自定义 agent
提供独立的文件存储空间。

WorkspaceMigration 表明这是从旧的 .agentscope/users/{userId}/workspace/agents/{agentId}/ 迁移而来。如果 builder 不支持多用户场景（或者你认为这个设计是不需要的），workspaces 目录以及
WorkspaceManagerFactory/NamespacedFilesystemView/BuilderWorkspaceConfig 相关逻辑可以删除。

---
2. admin/agents/ vs agents/

从代码来看，不存在硬编码的 admin/agents/ 目录路径。admin 只是 UserStore 中的一个默认用户名（username="admin", role=["user","admin"]）。

你看到的 admin/agents/ 实际上是：

.agentscope/workspaces/users/admin/agents/{agentId}/   ← WorkspaceManagerFactory 为用户 "admin" 创建的

而裸的 agents/ 有两个不同含义：

- workspace 内部的 agents/ 子目录：由 WorkspaceManager 管理，路径为 workspace/agents/{agentId}/sessions/、workspace/agents/{agentId}/tasks/。这是 HarnessAgent
存储子agent的会话日志和任务记录的地方（WorkspaceConstants.AGENTS_DIR = "agents"）。
- 用户定义列表：UserAgentDefinitionStore 存储在 .agentscope/users/{userId}/agents.json（注意这是一个 JSON 文件，不是目录）。

所以你看到的两个 agents 目录是不同层次的东西：
- .agentscope/workspaces/users/admin/agents/{agentId}/ = 用户 "admin" 拥有的 agent 的 workspace root
- 该 workspace 内部的 agents/ = 该 agent 的子agent运行时数据（sessions/tasks/context）

---
3. sessions/、sessions.json 和 agents/<agentId>/sessions/ 的关系

系统中存在 两套不同的 session 机制：

┌───────────────────────────────────────────────────────┬───────────────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                         位置                          │                创建者                 │                                              用途                                               │
├───────────────────────────────────────────────────────┼───────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤
│ <workspace>/sessions/                                 │ BuilderBootstrap (line 538-539)       │ JsonSession 的存储目录，用于 SessionAgentManager 做 agent 状态持久化（序列化                    │
│                                                       │                                       │ memory_messages.jsonl 等）                                                                      │
├───────────────────────────────────────────────────────┼───────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤
│ <workspace>/sessions.json                             │ BuilderBootstrap (line 540-541)       │ SessionStore 元数据索引——记录每个会话的 sessionKey、gateKey、agentId、userId、lastActivity      │
│                                                       │                                       │ 等路由/生命周期信息                                                                             │
├───────────────────────────────────────────────────────┼───────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤
│ <workspace>/agents/<agentId>/sessions/sessions.json   │ WorkspaceManager.updateSessionIndex() │ HarnessAgent 内部的子agent会话索引，记录 summary + updatedAt                                    │
├───────────────────────────────────────────────────────┼───────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤
└───────────────────────────────────────────────────────┴───────────────────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────┘

- agents/<id>/sessions/ = HarnessAgent 级别的运行时数据，记录单个 agent（通常是子agent）的对话历史和 summary index

另外，WorkspaceSession（HarnessAgent 默认的 session 实现）把状态存在：
<workspace>/[namespace/]agents/<agentId>/context/<sessionId>/{key}.json|jsonl
这是第三条路径（context/ 而非 sessions/），用于 StateModule.saveTo() 做 agent 内部状态持久化。

---
总结建议

1. workspaces 目录：如果确定不需要多租户隔离，可以删除 WorkspaceManagerFactory、NamespacedFilesystemView、BuilderWorkspaceConfig 中 workspaces 相关逻辑以及 WorkspaceMigration。
2. admin/agents/：不是独立概念，只是 workspaces/users/admin/agents/ 的表现。如果去掉 workspaces 方案，这个自然就消失了。
3. Sessions 三层结构：有一定冗余——BuilderBootstrap 的 sessions/ + sessions.json 是网关路由层的需求（跟踪所有活跃 session 做 idle-reset/daily-reset），而 agents/<id>/sessions/ 是 workspace 内部的
agent 运行时日志。两者服务不同目的，但命名确实容易混淆。