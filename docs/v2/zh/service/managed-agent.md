---
title: "Managed Agent：托管执行与能力扩展"
---

[English](/v2/en/service/managed-agent)

Managed Agent 由 Service 管理 Harness、会话和模型执行。你配置职责、模型与资源，无需为每个 Agent 部署独立应用。适合知识问答、资料处理和使用平台工具完成的工作。

## 准备并创建

先完成[安装](/v2/zh/service/quickstart)，准备模型凭据和一个可用 [Environment](/v2/zh/service/environments)。创建 Agent 时选择 **AgentScope Managed**，填写 Instructions；Model 可使用默认模型或所配置 provider 的标识。在 Advanced settings 选择环境，保存后用 [Chat](/v2/zh/service/chat) 验证第一轮回复。

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
| MCP 工具 | 在 Tools 配置连接，用 Vault 引用凭据 | endpoint、认证、工具权限与确认行为 |
| Skill | 在 Workspace/Skills 添加说明及辅助文件 | Agent 能否发现并实际使用文件 |
| Subagent | 在 Subagents 定义专项角色 | 委派边界、上下文与结果回传 |
| Team 成员 | 把 Agent 加入 Team 并指定角色 | 独立派发能力、资源和协作结果 |

Skill 描述流程，不自动安装系统依赖。Subagent 属于 Agent 内部委派；Team 提供跨成员的持久工作协作，两者适用层次不同。

## 从对话到交付

先验证纯问答，再验证只读工具，最后分派带验收标准的 Issue。执行出现工具确认或等待 Worker 时，检查真实等待原因；不能一律作为模型卡住处理。

任务结束后检查产物与 Issue 状态。对于人工验收，成功执行之后还要在 Inbox 接受结果。详细结果语义见[任务结果与失败处理](/v2/zh/service/managed-harness-task-outcomes)。

## 调整与运营

调整模型、技能或共享资源后，用新工作验证。保留一次正常运行的输入与结果作为回归样例，检查工具调用、凭据使用和输出质量。出现故障时携带 Issue/Run/Session ID 追查，按[排障指南](/v2/zh/service/troubleshooting)区分模型、环境和权限问题。
