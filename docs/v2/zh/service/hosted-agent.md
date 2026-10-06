---
title: "Hosted Agent：概览与用法"
en_link: /v2/en/service/hosted-agent
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Hosted Agent 使用你电脑或服务器上已经安装的 Coding Agent。Service 负责工作、调度和协作记录；Runtime Host 在本机启动 provider 并回传结果。模型、工具和 provider 账号由你管理。

首次使用请先按[操作指南](/v2/zh/service/connect-hosted-agent)完成创建或接入。本分类集中提供详细配置、支持能力和工作原理。

## 本章节

- [安装与连接 Runtime Host](/v2/zh/service/runtime-host)
- [主机与 Runtime 参数](/v2/zh/service/hosted-agent-configuration)
- [支持的 Provider 与能力差异](/v2/zh/service/hosted-agent-providers)
- [工作原理与恢复](/v2/zh/service/hosted-agent-execution)

## 准备主机

在目标主机安装并登录要使用的 provider，例如当前 Host 能发现的 Codex、Claude Code 或 Qoder。先在本机完成一个简单请求，确认 provider 的模型与授权可用，再[安装并连接 Runtime Host](/v2/zh/service/runtime-host)。

```bash
agentscope connect https://agentscope.example.com
agentscope runtime status
agentscope runtime probe
```

连接后应看到 Host 在线且 provider 探测成功。Host 在线不等于 provider 已登录或所有工具权限都可用。

## 创建 Hosted Agent

调用 `GET /api/v1/agents/runtime-options?tenant=...&namespace=...`，从 `runtimes` 选择 provider 并取得 `runtimeProfileId`、`runtimePoolId`。再调用 `POST /api/v1/agents`，设置 `binding.kind: "hosted-runtime"`，将两个 ID 放入 `binding.configuration`，在 `definition` 中填写名称、职责和所需 Workspace。完整请求见[创建指南](/v2/zh/service/connect-hosted-agent)。

成功响应的 `agent.id` 是分派、Team 成员和 Endpoint 引用的稳定身份。读取 `/api/v1/agents/{agentId}/hosted-settings` 可查看实际绑定与并发设置；所有管理操作使用有权访问目标 namespace 的平台凭据。参数、响应和版本更新规则见[配置参考](/v2/zh/service/hosted-agent-configuration)。

## 交付一个小任务

通过 [Issue API](/v2/zh/service/issues) 创建任务，设置 `assigneeType: "agent"`、`assigneeRef: agent.id`，要求“读取提供的 README，输出三条改进建议，不修改文件”。用 `/api/v1/agent-tasks/{taskId}` 及 `/api/v1/execution-attempts?tenant=...&namespace=...&taskId=...` 检查执行，再读取 Issue 评论和 Artifact。需要交付文件时上传 Artifact，方便主机之外的协作者读取。

任务工作目录由 Host 管理，默认位于其状态目录下。不要把它当成你已打开的本地 Git checkout；仓库内容和分支必须按任务的工作区配置准备。

## 扩展能力

Workspace 可以提供可移植的指令、技能和工具定义，适配器将支持的内容映射到 provider。安装额外 CLI、登录第三方系统和工具权限仍需在目标主机完成。不能只修改 Instructions 就获得主机未安装的程序。

Host 为任务提供 `agentscope-collaboration` MCP 或任务范围 CLI，用于读取工作、汇报进度、评论和上传产物。代码修复类团队可把 Hosted Agent 用作实施者，与 Managed 研究或审阅角色协作。详见 [Team 协作](/v2/zh/service/team-collaboration)。

## 中断与恢复

停止 Host 前先检查正在执行的工作。取消请求需要传递到 provider，观察 Attempt 最终状态确认。重启保留 Host 身份和状态目录；重新执行会产生新的 Attempt，不能假设会自动保留另一后端的进程内上下文。

没有可选 Runtime 时检查 `runtime probe`；工具被拒绝时检查 provider 登录与权限设置；已经完成却无产物时检查 Artifact 上传和回传日志。

## 作为服务对外调用

将该 Agent 或它参加的 Team、Workflow 发布为 [Endpoint](/v2/zh/service/endpoints)，应用通过统一 [Agent API](/v2/zh/service/service-api) 发起调用，读取 snapshot、Artifact 与 SSE。Hosted 原生 Session resume 表示 provider 在满足条件时能恢复自身会话，不等于统一调用 API 已提供任意 checkpoint 恢复；接入时查询 Endpoint/invocation capabilities。

通过界面完成相同管理操作，见 [Console：Agent 管理](/v2/zh/service/console/agents)。
