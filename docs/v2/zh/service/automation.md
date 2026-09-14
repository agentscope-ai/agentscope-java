---
title: "Automations：按计划与事件执行"
---

[English](/v2/en/service/automation)

Automation 把“何时触发”和“执行什么工作”保存为一条可复用规则。适合日报、定期检查和外部事件处理。需要固定多步骤拓扑时使用 [Workflow](/v2/zh/service/workflows)；Automation 主要负责触发 Agent 或 Team。

## 创建工作日报

在 **WORK → Automations** 新建规则，按下面的示例配置：

| 字段 | 示例与作用 |
| --- | --- |
| Name | Daily engineering digest |
| Runbook | 汇总指定项目的最新进展，标明来源和待确认事项，输出日报 |
| Context | 项目或文档地址，每行一项；目标 Agent 必须有实际读取能力 |
| Assignee | 选择可运行的 Agent 或 Team |
| Output | Create issue 保留可协作的工作项；Run only 只保留自动化执行记录 |
| Completion policy | Require human review 用于需要验收的产出；自动完成用于可自动判断的工作 |
| Schedule | `0 9 * * 1-5`，工作日 09:00 |
| Time zone | `Asia/Shanghai`；不要依赖浏览器或服务器的默认时区 |

检查计划预览给出的未来触发时间。先保持规则关闭并使用 **Test run**，阅读 Runbook、执行结果和产物，再启用规则。Test run 会实际执行工作，可能调用模型和工具。

## 控制重复与积压

**Skip** 在已有运行占用时跳过新触发；**Queue** 顺序排队，适合每个事件都需要处理的工作。设置 Queue timeout 限制过期工作积压，用 Run timeout 限制单次执行时长。队列等待与运行超时是两个不同阶段。

在 Runs 中检查 status、waitReason、输入、输出、错误和关联 Issue。关闭规则用于停止后续自动触发；要停止某次已存在的运行，应使用该 Run 的 Cancel。

## 使用 Webhook

添加 Webhook trigger，从详情复制对应 URL，保存创建或轮换时显示的 secret。发送 JSON 对象或数组，同时携带 `X-Automation-Secret` 和稳定的 `Idempotency-Key`：

```bash
curl --fail-with-body "$AUTOMATION_WEBHOOK_URL" \
  -H 'Content-Type: application/json' \
  -H "X-Automation-Secret: $AUTOMATION_SECRET" \
  -H 'Idempotency-Key: build-2026-09-10-001' \
  -H 'X-Event-Type: build.completed' \
  --data '{"project":"example","result":"passed"}'
```

这些环境变量由你填写为详情中的值。重传同一事件使用相同 key 和请求内容；不同事件使用新 key。事件筛选填写 `build.completed` 等名称，留空接受全部事件。JSON 内的 `event` 字段可以指定事件类型。

轮换 secret 后同步更新发送方。标准第三方 webhook 不一定能发送该认证头，必要时使用你管理的适配服务转换请求。

## 排查与重新执行

Deliveries 回答“事件是否收到、是否被过滤”；Runs 回答“工作是否执行、结果如何”。事件被接收不等于工作完成。确认规则、trigger、事件筛选和运行时可用性后，再考虑 Replay delivery 或 Rerun；它们会产生新的处理机会，可能重复业务副作用。

下一步：[Issues](/v2/zh/service/issues) · [Team 协作](/v2/zh/service/team-collaboration)。
