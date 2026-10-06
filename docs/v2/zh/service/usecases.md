---
title: "场景案例"
description: 从业务入口、API 调用、交付结果与验收方式出发，设计 Agent as a Service 应用。
en_link: /v2/en/service/usecases
---

<Note>
此为预览文档。以下是基于当前 API 组织的接入方案；行业案例用于说明使用形态，不代表相关企业使用了 AgentScope Service，也不代表这些业务集成已开箱即用。
</Note>

Agent as a Service 的接入通常从一个具体的业务动作开始：生成方案、调查异常、核查材料、回答客户问题，或定期研究一组对象。应用向 Endpoint 提交工作，通过 Invocation 持续观察和交互，最后将结果接回自己的业务流程。

先选**谁触发、输入是什么、交付如何验收**，再决定内部由单个 Agent、Team 还是 Workflow 执行。

## 选择使用形态

| 场景 | 用户看到的入口 | API 形态 | 主要交付 |
| --- | --- | --- | --- |
| [应用内生成方案与文件](#应用内生成方案与文件) | CRM、任务看板或文档页中的操作 | Job + 快照 / SSE | 报告、方案、文件与来源 |
| [故障调查与代码修复](#故障调查与代码修复) | 告警、Issue 或“生成修复”按钮 | Job + Webhook | 诊断、PR、测试证据 |
| [文档核验与流程质检](#文档核验与流程质检) | 文档处理流水线的检查节点 | Job + 结构化结果 | 问题清单、证据定位、复核报告 |
| [交互式业务助手](#交互式业务助手) | 产品中的聊天窗口 | Conversation + 每轮 Invocation | 回复、查询结果、待确认事项 |
| [周期研究与批量后台任务](#周期研究与批量后台任务) | 定时计划或业务事件 | 多个独立 Job | 每个对象的结果与异常 |
| [供其他 Agent 调用的专业服务](#供其他-agent-调用的专业服务) | 上层 Agent 的工具或流程节点 | Endpoint API；可由应用包装为工具 | 有契约的专业任务结果 |

## 一条共同的接入链路

先按[发布 Endpoint](/v2/zh/service/endpoints)准备运行目标、输入输出契约、Release 和 Application 凭证。下面的 `proposal` 是示例 slug；请求只有在该 Endpoint 已发布且其 schema 接受 `request` 时才能使用。

```bash
curl "$BASE_URL/invoke/v1/endpoints/proposal/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: opportunity-104-proposal-v1' \
  -H 'X-Correlation-ID: opportunity-104' \
  -d '{
    "title": "生成客户方案初稿",
    "input": {
      "request": "根据已授权的客户需求和产品资料，生成方案、来源清单与待确认事项；交由销售复核。"
    }
  }'
```

应用保存响应中的 Invocation ID，与自己的商机、订单或工单关联。后续使用统一接口：

| 阶段 | 应用动作 |
| --- | --- |
| 接受任务 | 将 HTTP 202 显示为已接单；网络重试使用相同幂等键及相同请求 |
| 展示进度 | 读取 Invocation 与 snapshot，从 snapshot 的 `as_of` 继续订阅 events；页面刷新不重新提交 |
| 等待人工 | 读取 required actions，由获授权人员答复；按 capabilities 开放补充输入、取消等操作 |
| 获取结果 | 查询 `invocation.status/result`，列出并下载实际 Artifact；后台系统也可接收 Webhook |
| 落地交付 | 校验结果和证据，按业务流程复核，再写回业务系统 |

认证、命令请求体、事件恢复、Webhook 签名及 SDK 示例见[统一服务 API](/v2/zh/service/service-api)。相同幂等键保护的是同一次服务提交；它不能自动保证发邮件、修改订单或提交代码等外部操作只发生一次。

## 应用内生成方案与文件

**业务目标：** 用户在 CRM 商机页点击“生成方案”，离开页面后任务继续，回来时看到可复核的方案和来源。营销简报、研究报告、演示材料也适用这一形态。

**行业参考：** Notion 将托管 Agent 融入任务板和文档工作流，用页面、Slack 消息和定时计划触发任务，产物直接回到产品中。[Notion 客户访谈](https://claude.com/customers/notion-qa)

**接入设计：**

1. 应用整理商机需求、获准读取的知识资料和交付要求，提交一个 Job。敏感资料由受控工具读取，正文中出现一个文件路径并不意味着 Agent 可以访问它。
2. 单个 Agent 起步；确需多人专业分工时，扩展为方案、实施规划与复核组成的 Team。调用接口不必随内部组织变化而变化。
3. 在原商机页面展示进度、待答问题、方案文件和引用来源。应用将 Invocation 与商机版本关联，避免把旧需求生成的方案覆盖新需求。
4. 业务人员核对承诺和资料后决定发送；执行完成不自动表示可以对客户作出承诺。

**验收：** 文件可实际下载，关键结论能定位到资料和版本，缺失信息明确列出，复核后才写入正式交付。资料读取失败应作为失败或待补充条件展示。

详细案例：[在 CRM 中生成客户方案](/v2/zh/service/cases/in-product-delivery)，包含固定商机资料、Job 请求、页面恢复与文件验收。

## 故障调查与代码修复

**业务目标：** 监控系统或研发平台将故障上下文交给服务，获得可审查的修复 PR；用户在原工单中跟踪处理。

**行业参考：** Sentry 的 Seer 先完成根因分析，再交给托管 Agent 规划修改、实现并创建 PR。[Sentry 案例](https://claude.com/customers/sentry)

**接入设计：**

1. 告警接收器完成事件归并，提交包含仓库、目标提交、诊断证据和修复范围的 Job。业务事件 ID 与修复轮次组成幂等键。
2. 执行环境准备仓库访问、构建工具和测试能力；可以使用 Managed Agent，也可以复用 Hosted Coding Agent。
3. 需要固定检查顺序时，用 Workflow 组织实现、测试和人工关卡。应用用 SSE 展示过程，以 Webhook 更新工单中的 PR 和测试链接。
4. 新证据和返工按原任务状态处理；已终止 Invocation 的修订应发起新任务并由应用关联。合并与部署遵守仓库现有规则。

**验收：** PR 对应预期仓库和提交，测试证据真实，失败与未覆盖项可见。Agent 写出“测试通过”或返回 PR URL，都不能替代 CI 与实际代码核查。

详细案例：[从告警到可审查 PR](/v2/zh/service/cases/incident-to-pr)，包含可复现的 Java 故障、提交契约、PR 与测试证据验收。

## 文档核验与流程质检

**业务目标：** 在已有 OCR、抽取或文档生成流水线后增加一个核验步骤，对照原始材料和规则检查遗漏、矛盾与证据，输出供专家复核的问题清单。

**行业参考：** Wisedocs 将 Managed Agents 用于文档验证，保留原有专用处理流水线，组合原始 PDF、抽取结果和 SOP 进行检查。[Wisedocs 工程文章](https://www.wisedocs.ai/blogs/building-managed-agents-for-document-verification)

**接入设计：**

1. 上游准备原始资料的受控引用、已有处理结果、规则版本和核验范围。当前公共 Job 接口接收 JSON；大文件读取需要业务文件工具或相应运行时文件机制。
2. 按文档或案件提交 Job。可将 `findings`、`source_refs`、`review_required` 等设计为业务输出字段，配置 Endpoint 的 output schema 与 result mapping；这些名称不是平台保留字段。
3. Agent 读取证据并生成问题清单和报告；固定规则继续使用确定性程序验证，复杂歧义交给人工复核。
4. 上游保存核验结果，只有满足业务验收规则的文档才能进入下一阶段。

**验收：** 每项问题能够定位原文，无法读取的页面不可算作已核验；使用有标注样本评估漏检和误报。schema 通过只证明结构符合契约，不证明事实正确。

详细案例：[文档核验与质量检查](/v2/zh/service/cases/document-verification)，对照固定原文、错误抽取值与规则，验证结构化问题清单和复核流程。

## 交互式业务助手

**业务目标：** 用户在产品内连续咨询、补充条件、查询业务数据，并在必要时转交人工或确认操作。适合售前咨询、客户支持和企业内部助手。

**行业参考：** Anthropic 的 Buying Agent 处理销售咨询并在适当时机转交人工，说明托管 Agent 也可承接连续交互的业务入口。[Buying Agent 实践](https://claude.com/blog/how-anthropics-sales-team-rebuilt-inbound-with-claude-managed-agents)

**接入设计：**

1. 发布 conversation 模式的单 Agent Endpoint，应用建立 Conversation，并保存业务用户与会话的关联。Team 与 Workflow 当前使用 Job 模式。
2. 每轮请求产生新的 Invocation，在同一 Conversation 内保留上下文；同一会话一次只运行一个 Invocation。
3. 用快照与 SSE 恢复聊天界面和待处理交互。仅展示当前 capabilities 支持的操作，人工操作还需符合指定审批人的权限。
4. 用户发起独立长任务时，可由应用另开 Job，持续展示其状态；人工转接由应用对接客服或工单系统。

**验收：** 刷新和重连不重复发起轮次；用户不能读取他人的会话；审批与取消到达实际终态。应用后端保存调用密钥，并校验每次会话访问。共享 Application 的多把 API key 仍是同一应用身份，不能代替终端用户隔离。

详细案例：[业务页面中的交互式助手](/v2/zh/service/cases/business-assistant)，包含用户与订单权限、首轮请求、后续轮次及待办处理。

## 周期研究与批量后台任务

**业务目标：** 每天研究一批客户、巡检一组服务，或对新进入队列的业务对象执行独立任务。用户主要查看完成结果和异常。

**行业参考：** OpenAI Agents API 发布材料包含 Nash 的长时间物流工作流，以及 Dwelly 对批量异步任务的测试。两者证据阶段不同，测试案例不能当作规模化生产承诺。[OpenAI 发布说明](https://openai.com/index/introducing-the-agents-api/)

**接入设计：**

1. 外部调度器或事件消费者枚举待处理对象，为每个对象提交一个 Job，例如“客户 ID + 研究日期 + 策略版本”形成幂等键。
2. 保存业务批次与 Invocation 的映射，限制并发；遇到配额拒绝按退避策略重试。处理失败对象时建立明确的重试记录。
3. 后台通过查询或 Webhook 收集结果，校验来源时效并更新业务系统。Webhook 接收端验签，并按事件 ID 去重。
4. 只将需要决策的结果推送给用户；调度器负责周期和批次状态，Service 负责每次调用的执行。

平台 [Automation](/v2/zh/service/automation)可以创建 Issue、添加评论、启动或通知 Workflow。它目前不是直接调用公开 Endpoint 的统一触发入口；需要 Application 权限、Release 和 Invocation 链路时，由调度器调用公开 API。

**验收：** 重复事件不重复创建逻辑工作，批次可以对账，单个失败不掩盖其余结果。应用级 token budget 按已上报用量执行限制，应结合实际费用核对；它不是精确预扣的账单上限。

详细案例：[按业务对象运行周期研究](/v2/zh/service/cases/scheduled-research)，包含固定来源快照、批次账本、幂等重试、通知与对账。

## 供其他 Agent 调用的专业服务

**业务目标：** 主 Agent 负责理解需求，把“调研供应商”“核验合同资料”或“分析订单异常”作为一项专业能力调用，取回结构化结论和证据。

**行业参考：** Pendo 描述了由 MCP 入口触发后台托管 Agent 的使用方式，说明服务的调用方也可以是另一个 Agent。[Pendo Novus 案例](https://claude.com/customers/pendo-qa)

**接入设计：**

1. 将专业能力发布为独立 Endpoint，定义范围、输入输出 schema、超时及用量限制。
2. 主 Agent 的工具适配器提交 Job 并保存 Invocation ID。长任务先返回任务句柄，再提供查询、交互或取消操作，避免一直占用同步工具请求。
3. 适配器将结果与来源交回主 Agent；主 Agent 根据专业结果继续自己的流程。
4. 需要 MCP 时，由应用提供对应 MCP 工具包装。Service 的协作 MCP 服务用于内部任务协作，不能据此推断所有 Endpoint 已自动导出为公共 MCP 工具。

**验收：** 上层能够处理等待、失败、部分成功和取消；嵌套调用有明确预算与深度限制；终端用户授权通过受控工具链传递。不能把主 Agent 的一段提示词当作下游数据访问权限。

详细案例：[供上层 Agent 调用的专业服务](/v2/zh/service/cases/agent-as-tool)，包含专业输入、异步工具契约、任务句柄与控制传播。

## 如何选择第一个落地场景

优先选择输入可取得、工具已接通、交付可检查的一个任务，例如方案初稿、只读异常调查或文档质量检查。先用单 Agent 验证“提交—执行—取回—复核”，再按实际需要增加协作、固定流程和自动触发。

六篇详细案例分别提供固定输入、API 调用、应用集成步骤和验收方法：

| 详细案例 | 可验证的交付 | 应用需要接入 |
| --- | --- | --- |
| [CRM 方案与文件](/v2/zh/service/cases/in-product-delivery) | 方案、问题清单和来源版本 | 商机权限、页面、文件复核与发送 |
| [告警到 PR](/v2/zh/service/cases/incident-to-pr) | 修复代码、提交与测试证据 | 告警归并、仓库、CI 与审查 |
| [文档核验](/v2/zh/service/cases/document-verification) | 字段矛盾、原文定位与复核状态 | 原始资料、抽取流水线与领域规则 |
| [交互式助手](/v2/zh/service/cases/business-assistant) | 连续查询、确认与真实工单 | 用户身份、业务工具、聊天界面 |
| [周期研究](/v2/zh/service/cases/scheduled-research) | 每个对象的结果与批次对账 | 调度器、来源读取、写回与通知 |
| [专业 Agent 工具](/v2/zh/service/cases/agent-as-tool) | 任务句柄、专业结论与证据 | 上层工具适配、权限及取消传播 |

教程中的业务资料是虚构样例；预期输出与行业公开案例都不构成 AgentScope 的实跑记录。上线验收应保存输入版本、Release、Invocation、来源与真实产物，并验证质量、成本、耗时、权限及中断恢复。
