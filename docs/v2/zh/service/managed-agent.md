---
title: "Managed Agent：概览与用法"
en_link: /v2/en/service/managed-agent
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Managed Agent 由 Service 管理 Harness、会话和模型执行。你配置职责、模型与资源，无需为每个 Agent 部署独立应用。适合知识问答、资料处理和使用平台工具完成的工作。

首次使用请先按[操作指南](/v2/zh/service/create-managed-agent)完成创建或接入。本分类集中提供详细配置、支持能力和工作原理。

面向业务应用发布能力，使用 [Endpoint 与统一服务 API](/v2/zh/service/endpoints)，与 External、Hosted、Team 共用调用方式。需要直接管理托管会话、文件和 checkpoint 时，再使用 [Managed 原生会话 API](/v2/zh/service/session-event-log)。服务负责后台执行，客户端负责提交、展示和交互。

[可恢复聊天示例](/v2/zh/service/agent-api-chat)把创建、发送、刷新恢复、工具确认和取消串成一条完整接入流程。

## 本章节

- [参数与模型配置](/v2/zh/service/managed-agent-configuration)
- [支持的能力与接入类型](/v2/zh/service/managed-agent-capabilities)
- [工作原理](/v2/zh/service/managed-agent-execution)
- [Workspace：共享能力定义](/v2/zh/service/workspaces)
- [Environment：执行位置](/v2/zh/service/environments)
- [Memory：共享知识](/v2/zh/service/memory)
- [Vault：工具凭据](/v2/zh/service/vault)

## 准备并创建

先完成[安装](/v2/zh/service/quickstart)，准备模型和可用 [Environment](/v2/zh/service/environments)。调用 `POST /api/v1/agents`，提供 `binding.kind=managed` 和 `definition`，再通过会话或 Endpoint 提交验证任务。完整请求见[创建指南](/v2/zh/service/create-managed-agent)，字段与版本更新规则见[参数参考](/v2/zh/service/managed-agent-configuration)。页面入口见 [Console](/v2/zh/service/console/agents)。

Instructions 示例：

```text
你负责整理技术资料。先说明输入是否足够，引用资料支持结论。
只读取任务指定的文件；缺少材料时列出问题。
最终交付结论、证据与待确认事项。
```

模型凭据、工具凭据和用户登录凭据用途不同。服务安装包不包含模型额度，配置 Vault 也不会自动替代所有模型 provider 的连接配置。

## 增加文件与知识

关联 Workspace 后，用小任务读取一份已知文件。需要隔离 Shell/文件操作时，选择 sandbox 或 self_hosted 环境。Local 工具运行在 Dataplane 中；它不会自动访问浏览器电脑或任意宿主目录。

将长期知识放入 Memory Store 并绑定。Agent 按需通过工具读取共享文档；会话工作记忆只属于执行上下文。上传大文件后让 Agent给出可核对的片段或统计，避免只凭“已读取”的回复判断成功。

## 扩展工具、技能与子 Agent

| 能力 | 如何添加 | 应检查什么 |
| --- | --- | --- |
| MCP 工具 | 通过 definition 的 tools/mcpServers 配置连接，引用 Vault | endpoint、认证、工具权限与确认行为 |
| Skill | 通过 Workspace 文件与 Skill API 添加说明和文件 | Agent 能否发现并实际使用文件 |
| Subagent | 通过 Workspace Subagent API 或 multiagent 配置专项角色 | 委派边界、上下文与结果回传 |
| Team 成员 | POST Team members，传 Agent ID 与角色 | 独立派发能力、资源和协作结果 |

Skill 描述流程，不自动安装系统依赖。Subagent 属于 Agent 内部委派；Team 提供跨成员的持久工作协作，两者适用层次不同。

## 从对话到交付

先验证纯问答，再验证只读工具，最后分派带验收标准的 Issue。执行出现工具确认或等待 Worker 时，检查真实等待原因；不能一律作为模型卡住处理。

任务结束后检查产物与 Issue 状态。对于人工验收，成功执行之后还要通过 Issue accept/reject API 作出验收决定。详细结果语义见[任务结果与失败处理](/v2/zh/service/managed-harness-task-outcomes)。

## 调整与运营

调整模型、技能或共享资源后，用新工作验证。保留一次正常运行的输入与结果作为回归样例，检查工具调用、凭据使用和输出质量。出现故障时携带 Issue/Run/Session ID 追查，按[排障指南](/v2/zh/service/troubleshooting)区分模型、环境和权限问题。
