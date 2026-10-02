---
title: "JEV 应用与 Harness 改造指南"
---


本专题面向 AgentScope Java 的实际落地，也覆盖不依赖 Agent Loop 的业务服务、检索流水线和离线评估。目标是把类型化语义判断做成可以测量、替换和回退的能力，再按场景接入。

**状态：增量实现中。** 已有客户端、Judge，现新增公共运行模式、三个Harness中间件改造、Service配置与判断事件，以及显式调用的应用组件。应用建议API不等于生产自动接管；具体已实现范围、限制和验证记录见各页。原始代码基线为`8d9bec688a002537df93a0c288e225c10078fe43`。


## 阅读导航

| 目标 | 文档 |
| --- | --- |
| 查看新增 Agent 集成 | [内容护栏](/v2/zh/jev/guides/content-guardrail-api)、[最终草稿修订](/v2/zh/jev/guides/answer-refinement-api)、[检索适配](/v2/zh/jev/guides/knowledge-adapter-api)、[评估接口](/v2/zh/jev/guides/evaluator-api)、[离线组合案例](/v2/zh/jev/guides/agent-integration-example) |
| 使用公共模式与离线Harness案例 | [公共执行API](/v2/zh/jev/guides/harness-runtime) |
| 使用三项Harness能力 | [工具选择](/v2/zh/jev/guides/tool-selection-api)、[执行防护](/v2/zh/jev/guides/tool-guard-api)、[模型路由](/v2/zh/jev/guides/model-routing-api) |
| 配置Service并查询判断 | [Service API](/v2/zh/jev/guides/service-api) |
| 运行客服、RAG及高级建议案例 | [应用API入口](/v2/zh/jev/guides/application-api) |
| 看清现状、复用点和范围 | [现状与总体架构](/v2/zh/jev/architecture) |
| 理解题型、概率、置信度与弃权 | [核心概念与决策契约](/v2/zh/jev/concepts) |
| 用现有客户端跑第一个判断 | [客户端、批量与故障处理](/v2/zh/jev/guides/client) |
| 使用已实现的Judge | [基本API与客服案例](/v2/zh/jev/guides/judge-api) |
| 做评审和有限自动修订 | [评审与质量闭环](/v2/zh/jev/guides/judge) |
| 做动作前检查与内容检查 | [护栏与执行授权](/v2/zh/jev/guides/guardrails) |
| 做检索过滤、排序、引用复核 | [RAG 与证据处理](/v2/zh/jev/guides/rag) |
| 做工具筛选与模型路由 | [工具、模型与阶段路由](/v2/zh/jev/guides/routing) |
| 做上下文与长期记忆治理 | [上下文、压缩与记忆](/v2/zh/jev/guides/context-memory) |
| 做长任务和团队交付监督 | [进展监督与 TeamHarness](/v2/zh/jev/guides/supervision-teams) |
| 选业务试点 | [应用地图](/v2/zh/jev/applications/index)、[客服](/v2/zh/jev/applications/customer-support)、[知识助手](/v2/zh/jev/applications/knowledge-assistant)、[研发与数据运营](/v2/zh/jev/applications/engineering-data) |
| 定评测口径、上线与回退 | [评测与实测证据](/v2/zh/jev/evaluation)、[运行与治理](/v2/zh/jev/operations) |
| 查看本轮构建与验收 | [验证记录](/v2/zh/jev/implementation-validation) |
| 拆任务、进入研发 | [实施路线和验收](/v2/zh/jev/implementation-plan) |
| 从GitHub案例选择实施工作项 | [案例与规划映射](/v2/zh/jev/applications/github-cases) |
| 找开源参考与差异 | [参考项目及来源](/v2/zh/jev/references) |

## 三条使用路线

1. **应用先行**：业务服务 → JevClient → 原子问题 → 业务规则。先做工单分流、资料筛选和输出审核，不需要 Agent 或 Harness。
2. **框架增强**：沿用现有 Middleware，在明确的调用边界插入判断；先影子运行，再接管一个低影响动作。
3. **平台运营**：把定义版本、评测、轨迹和策略与AgentScope Service 的运行与评估链路 关联。当前已接入 Service 用途配置与判断事件；业务闭环和生产运行验收仍需逐项推进。

## 首批试点

选择“客服意图与条件提取”“知识助手证据筛选”“研发任务交付检查”三个端到端试点；共享客户端、判定状态、观测与评测格式。先构建跨场景可验证的纵向流程，再沉淀公共抽象，避免先造一个无人使用的中心服务。

文档结构参考 spring-ai-typesafe 的概念、客户端、Judge、Guardrail、RAG、Tool Search、组合模式与示例分层；组件设计按本仓库 Reactor/Middleware/RuntimeContext 实际机制重新组织。[参考依据](/v2/zh/jev/references)

本专题已进入正式中文 JEV 导航，使用指南、设计背景、实施计划与验证记录分别编组。

## 文档维护与验证

正式站点页面位于 `docs/v2/zh/jev`，由 `docs/docs.json` 的 JEV 栏目导航。运行 `npm run validate --prefix docs` 与 `npm run broken-links --prefix docs` 检查。

本专题的实现与历史验证对应提交 `be4a9643`，已通过合并提交 `6e4d2bb6` 进入 `harness-context-redesign`。合并后的回归验证尚未完成；历史验证范围见验证记录。
