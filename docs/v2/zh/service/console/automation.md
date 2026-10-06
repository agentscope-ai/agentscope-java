---
title: "自动化与消息渠道"
en_link: /v2/en/service/console/automation
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Agent 或 Team 已能完成一次工作后，可以让它按计划执行，或在收到外部消息时开始处理。**Work → Automations** 管理定时与 Webhook 触发，**Design → Channels** 管理消息平台连接、接待与路由。两者都使用平台资源 API，应用可以按 [Automation](/v2/zh/service/automation) 与 [Channel](/v2/zh/service/channels)指南直接配置。

## 创建定时工作

以工作日报为例，打开 **Work → Automations → New automation**。先写 Runbook，说明每次需要读取什么、如何汇总，以及缺少资料时如何处理；然后选择执行者和触发计划。

| 字段 | 示例 |
| --- | --- |
| Name | Daily engineering digest |
| Runbook | 汇总指定项目的最新进展，标明来源、阻塞项和待确认事项，交付日报 |
| Context links | 项目或文档地址，每行一项 |
| Assignee | 选择已经验证可执行该工作的 Agent 或 Team |
| Output mode | Create issue 用于需要讨论和验收的工作；Run only 仅保留自动化运行 |
| Completion policy | 需要人工验收时选择 Require human review |
| Schedule / Time zone | `0 9 * * 1-5` / `Asia/Shanghai`，每个工作日 09:00 |

Context links 是任务资料，执行者仍需有实际读取这些地址的工具和权限。控制台当前的 Assignee 表单提供 Agent 与 Team；已有规则的高级 action 配置通过 API 编辑。

<Frame caption="自动化配置示例，使用固定演示数据。">
  <img src="/imgs/service/automation.png" alt="Automation 的 Runbook、执行者和触发设置" />
</Frame>

保存前查看 **Next runs**，核对时区和未来触发时间。可以先保持规则关闭，保存后使用 **Test run** 验证产物，再启用规则。规则启用后，同一操作显示为 **Run now**；两者都会实际执行工作。

<Frame caption="计划预览显示指定时区中的后续触发时间。">
  <img src="/imgs/service/automation-schedule.png" alt="Cron、时区与下次触发时间" />
</Frame>

## 跟进运行与重复触发

在详情的 Runs 中打开一次运行，检查状态、输入、结果、错误及关联 Issue。需要停止后续触发时使用规则的 **Pause**；要停止已经产生的运行，应进入该 Run 使用 **Cancel run**。

在 Advanced settings 中，Overlapping runs 的 **Skip** 会在已有运行占用时跳过新触发，**Queue** 则按顺序排队。Maximum queue time 限制排队时间，Maximum execution time 限制运行时间。选择这些参数时，应明确日报可以跳过，而逐个处理业务事件通常需要保留顺序。

## 让外部事件触发工作

在 Automation 编辑窗口中添加 **Webhook** trigger，按需要填写 Events to accept，保存后从详情复制 Webhook URL，并保存创建或轮换时显示的 secret。调用方使用 `X-Automation-Secret` 认证，并提供 `Idempotency-Key` 区分事件；具体请求示例见 [Automation API](/v2/zh/service/automation)。

发送测试事件前，启用规则和对应 trigger。然后先查看 Webhook deliveries，确认事件是否接收、是否被筛选；再查看 Runs，确认工作是否执行。收到事件与完成工作是两个阶段，Runbook 应明确如何理解事件载荷以及预期产物。

需要再次处理时，先核对原始载荷、运行结果和已发生的业务操作，再使用 **Replay** 或运行详情的 **Run again**。轮换 secret 后还需要同步更新发送方配置。

这里的 Webhook 由外部系统调用 Service 来触发工作。希望 Service 将会话完成、等待输入等变化通知业务后端时，使用[会话 Webhook](/v2/zh/service/session-event-log)，它是另一种方向的事件接入。

## 连接消息平台

准备消息平台应用与凭据后，打开 **Design → Channels → New channel**，从部署提供的平台列表选择类型，填写平台字段，设置 Default Agent 与 Conversation isolation，再创建连接。按平台要求配置回调或其他连接方式；外部平台回调须使用能从平台访问的地址。

<Frame caption="Channel 目录示例，使用固定演示数据。">
  <img src="/imgs/service/channels.png" alt="消息平台 Channel 的类型与运行状态" />
</Frame>

进入 Channel 详情，在 **Transfer rules → Add rule** 配置需要特殊处理的用户、群组或事件，并选择目标 Agent。普通会话默认目标与转接规则共同决定接待对象；从 Agent 的 **Connections → Channels** 也可以查看相关连接。路由指向逻辑 Agent，不绑定某个运行实例。

保存后，从测试账号发送一条只读请求，检查消息是否到达、目标是否正确、回复是否回到外部平台，再继续验证后续消息和文件。`running` 表示渠道适配器已启动，不代表整条消息链路已经验证通过。

对于需要跟进的工作，在 Channel 详情的接待与工作回传区域检查关联 Issue 和回传状态，再到 [Issue](/v2/zh/service/console/tasks)继续讨论和验收。Agent 完成工作后，外部回复仍可能因平台发送权限或连接错误失败，应以实际回传记录为准。

对自己的业务应用提供 HTTP 服务时，使用 [Agent API](/v2/zh/service/service-api)或 [Endpoint](/v2/zh/service/endpoints)；Channel 用于连接和路由外部消息平台。
