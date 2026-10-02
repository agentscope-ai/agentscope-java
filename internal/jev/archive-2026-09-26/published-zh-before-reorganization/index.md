---
title: JEV 决策与 Agent 集成
description: 工具选择、内容护栏、草稿修订、检索和评估的 API 与可运行案例。
en_link: /v2/en/jev/index
---

JEV 为 Agent 提供类型化语义判断，生成模型继续负责回答，确定性代码负责权限与实际执行。这里汇集基本 API、Harness 与 Service 接入、离线案例、设计背景和验证记录。

<Note>
新增能力及历史验证记录对应提交 `be4a9643`，已通过 `6e4d2bb6` 合入 `harness-context-redesign`。合并后已完成 R1–R7 的研发验收：JEV 247 项、Harness 26 项、Service 45 项相关测试通过。新增源码适配、真实模型对照与生产限制见[逐项推进记录](/v2/zh/jev/reference-roadmap)。
</Note>

## 从可运行案例开始

<CardGroup cols={2}>
<Card title="离线组合案例" icon="play" href="/v2/zh/jev/guides/agent-integration-example">运行检索、审核、修订和批量评估，不需要模型密钥。</Card>
<Card title="客户端与决策契约" icon="code" href="/v2/zh/jev/guides/client">理解 Noul、Choice、Score，接入第一个判断。</Card>
</CardGroup>

## 接入 Agent 与 Service

<CardGroup cols={2}>
<Card title="工具选择与执行防护" icon="wrench" href="/v2/zh/jev/guides/tool-selection-api">工具适用性、none 语义、必要工具与失败回退。</Card>
<Card title="内容护栏" icon="shield" href="/v2/zh/jev/guides/content-guardrail-api">审核输入和输出，明确拒绝、复核和支持状态。</Card>
<Card title="最终草稿审核与修订" icon="pen" href="/v2/zh/jev/guides/answer-refinement-api">有限修订文本，避免重跑已执行工具。</Card>
<Card title="Service 配置与追踪" icon="gear" href="/v2/zh/jev/guides/service-api">按用途配置 OFF、SHADOW、ENFORCE，查询决策记录。</Card>
</CardGroup>

## 检索、评估与应用

- [Knowledge 与检索工具适配](/v2/zh/jev/guides/knowledge-adapter-api)：ACL 先行，筛选并重排授权证据。
- [评估接口与批量报告](/v2/zh/jev/guides/evaluator-api)：固定样本、错误与弃权统计。
- [客服应用](/v2/zh/jev/guides/support-api)：多意图分流与草稿评审。
- [代码评审与证据定位](/v2/zh/jev/guides/code-review-api)：变更/源码快照的分阶段只读评审。
- [浏览器只读执行与验证](/v2/zh/jev/guides/browser-execution-api)：真实页面守卫、授权候选与独立完成证据。
- [模型路由](/v2/zh/jev/guides/model-routing-api)：调用内粘性、能力约束与回退。

左侧导航还包括高级能力、GitHub 场景调研、[实施计划](/v2/zh/jev/implementation-plan)和[验证记录](/v2/zh/jev/implementation-validation)。未完成的生产验收与设计建议在对应页面单独说明。
