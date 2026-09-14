---
title: "Hosted Agent：连接你的 Coding Agent"
---

[English](/v2/en/service/hosted-agent)

Hosted Agent 使用你电脑或服务器上已经安装的 Coding Agent。Service 负责工作、调度和协作记录；Runtime Host 在本机启动 provider 并回传结果。模型、工具和 provider 账号由你管理。

## 准备主机

在目标主机安装并登录要使用的 provider，例如当前 Host 能发现的 Codex、Claude Code 或 Qoder。先在本机完成一个简单请求，确认 provider 的模型与授权可用，再[安装并连接 Runtime Host](/v2/zh/service/runtime-host)。

```bash
agentscope connect https://agentscope.example.com
agentscope runtime status
agentscope runtime probe
```

连接后应看到 Host 在线且 provider 探测成功。Host 在线不等于 provider 已登录或所有工具权限都可用。

## 创建 Hosted Agent

在 **DESIGN → Agents** 创建 Agent，选择已发现的 Runtime，而不是 AgentScope Managed。填写职责、可选模型覆盖和 Workspace。页面会展示 runtime 的指令、工具、技能、子 Agent 等能力映射，按实际支持范围配置。

Hosted 使用 Runtime profile/pool 选择执行者。日常使用通过 Runtime 选择完成；需要多个执行主机时由管理员维护容量和策略，不把某台机器的路径写成所有 Host 共享的路径。

## 交付一个小任务

创建 Issue：“读取示例仓库 README，输出三条改进建议，不修改文件”。选择该 Agent，检查 Execution、provider 事件和最终评论。随后再验证需要文件写入的任务，并将交付文件上传为 Artifact，方便主机之外的协作者读取。

任务工作目录由 Host 管理，默认位于其状态目录下。不要把它当成你已打开的本地 Git checkout；仓库内容和分支必须按任务的工作区配置准备。

## 扩展能力

Workspace 可以提供可移植的指令、技能和工具定义，适配器将支持的内容映射到 provider。安装额外 CLI、登录第三方系统和工具权限仍需在目标主机完成。不能只修改 Instructions 就获得主机未安装的程序。

Host 为任务提供 `agentscope-collaboration` MCP 或任务范围 CLI，用于读取工作、汇报进度、评论和上传产物。代码修复类团队可把 Hosted Agent 用作实施者，与 Managed 研究或审阅角色协作。详见 [Team 协作](/v2/zh/service/team-collaboration)。

## 中断与恢复

停止 Host 前先检查正在执行的工作。取消请求需要传递到 provider，观察 Attempt 最终状态确认。重启保留 Host 身份和状态目录；重新执行会产生新的 Attempt，不能假设会自动保留另一后端的进程内上下文。

没有可选 Runtime 时检查 `runtime probe`；工具被拒绝时检查 provider 登录与权限设置；已经完成却无产物时检查 Artifact 上传和回传日志。
