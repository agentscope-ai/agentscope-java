---
title: "注册或者创建 Agent"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

**DESIGN → Agents** 管理 Agent 的身份、行为和执行方式。Agent 定义可以被 Chat、Issue、Team 和 Endpoint 复用；保存定义本身不会执行任务。

## 按运行方式接入

- **Managed Agent**：在控制台创建云端 Agent，再配置模型和资源。
- **AgentScope 框架**：注册你编写并部署的应用，以 External Agent 方式接入；任务执行能力需要相应适配。
- **Hosted**：连接 Runtime Host，选择已经安装并登录的 Coding Agent provider。

左侧三个子菜单用于完成创建、注册和验证。详细参数、资源配置与工作原理统一收录于下方的参考手册。完成接入后，继续[通过 Endpoint 分派任务](/v2/zh/service/endpoints)或[在控制台创建 Issue](/v2/zh/service/issues)。

## 界面导览

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/agents.png" alt="Agent 目录与 Managed、Hosted、External 筛选" />
</Frame>

顶部筛选用于区分运行方式，卡片说明 Agent 的用途。点击卡片进入配置；需要新能力时点击 **New agent**。示例中三类 Agent 并列展示，实际可用性仍需检查各自的运行环境。

## 选择运行方式

| 方式 | 谁运行 Agent | 如何接入 |
| --- | --- | --- |
| Managed | Service 的 Harness 与 Dataplane | 在控制台创建并选择 AgentScope Managed |
| Hosted | 你电脑或服务器上的 Coding Agent provider | 先连接 Runtime Host，再选择发现的 Runtime |
| External | 你独立部署的 Agent 应用 | 应用通过 SDK 注册，进入统一 Agent 目录 |

三种方式共享工作入口，但模型、工具、会话恢复和配置投影能力不同。不要把“目录中可见”当成“具备所有会话和派发能力”。接入细节见 [Managed](/v2/zh/service/managed-agent)、[Hosted](/v2/zh/service/hosted-agent) 和 [External](/v2/zh/service/external-agent)。

## 创建一个 Agent

1. 点击创建按钮，填写 Name、用途和 Instructions。
2. 按需要关联 Workspace，复用其中的操作说明、技能、工具和子 Agent 定义。
3. 在 Execution 中明确选择 Runtime。存在在线 Host 时页面可能优先选择 Hosted provider；体验托管 Agent 时请主动选择 **AgentScope Managed**。
4. Model 留空使用运行时默认值，或填写该 provider 接受的模型标识。
5. Managed Agent 在 Advanced settings 中选择 Environment；只有管理员允许 Local 时才使用自动本地默认环境。
6. 点击 **Create & open agent**，进入详情检查配置。

Agent key 是稳定身份，用于区分同一空间中的 Agent；显示名称用于让同事理解用途。

## 按详情菜单配置

| 页面 | 主要内容 | 验证方式 |
| --- | --- | --- |
| Behavior | 职责、指令与模型 | 发送职责边界明确的小请求 |
| Workspace | 关联资料与执行资源 | 读取一份已知文件 |
| Skills | 可复用任务流程和辅助文件 | 指定一个技能任务并核对输出 |
| Tools | 工具和 MCP 连接 | 执行只读工具调用，检查认证与确认策略 |
| Subagents | 可委派的专项能力 | 检查子任务结果如何返回主 Agent |
| Versions | 查看定义版本 | 用新工作验证目标版本 |
| Connections → Channels | 消息入口关联 | 从实际渠道发送请求并检查路由 |

## 一次只扩展一种能力

先验证纯对话，再加文件读取、一个外部工具和一个专项技能。将需要的凭据放入 [Vault](/v2/zh/service/vault)，共享知识放入 [Memory](/v2/zh/service/memory)。添加配置文件不会给运行时自动安装二进制工具，也不会授予外部系统权限。

修改共享 Workspace 可能影响多个 Agent。保存前查看消费者，保存后使用新 Chat 或 Issue 验证；正在运行的任务不应被假定会即时切换到新定义。

下一步：[Teams](/v2/zh/service/teams) · [Workspaces](/v2/zh/service/workspaces)。
