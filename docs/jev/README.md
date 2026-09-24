---
title: "JEV 应用与 Harness 改造指南"
---

# JEV 应用与 Harness 改造指南

本专题面向 AgentScope Java 的实际落地，也覆盖不依赖 Agent Loop 的业务服务、检索流水线和离线评估。目标是把类型化语义判断做成可以测量、替换和回退的能力，再按场景接入。

**状态：增量实现中。** 已有客户端、Judge，现新增公共运行模式、三个Harness中间件改造、Service配置与判断事件，以及显式调用的应用组件。应用建议API不等于生产自动接管；具体已实现范围、限制和验证记录见各页。原始代码基线为`8d9bec688a002537df93a0c288e225c10078fe43`。


## 阅读导航

| 目标 | 文档 |
| --- | --- |
| 查看新增 Agent 集成 | [内容护栏](guides/content-guardrail-api.md)、[最终草稿修订](guides/answer-refinement-api.md)、[检索适配](guides/knowledge-adapter-api.md)、[评估接口](guides/evaluator-api.md)、[离线组合案例](guides/agent-integration-example.md) |
| 使用公共模式与离线Harness案例 | [公共执行API](guides/harness-runtime.md) |
| 使用三项Harness能力 | [工具选择](guides/tool-selection-api.md)、[执行防护](guides/tool-guard-api.md)、[模型路由](guides/model-routing-api.md) |
| 配置Service并查询判断 | [Service API](guides/service-api.md) |
| 运行客服、RAG及高级建议案例 | [应用API入口](guides/application-api.md) |
| 看清现状、复用点和范围 | [现状与总体架构](architecture.md) |
| 理解题型、概率、置信度与弃权 | [核心概念与决策契约](concepts.md) |
| 用现有客户端跑第一个判断 | [客户端、批量与故障处理](guides/client.md) |
| 使用已实现的Judge | [基本API与客服案例](guides/judge-api.md) |
| 做评审和有限自动修订 | [评审与质量闭环](guides/judge.md) |
| 做动作前检查与内容检查 | [护栏与执行授权](guides/guardrails.md) |
| 做检索过滤、排序、引用复核 | [RAG 与证据处理](guides/rag.md) |
| 做工具筛选与模型路由 | [工具、模型与阶段路由](guides/routing.md) |
| 做上下文与长期记忆治理 | [上下文、压缩与记忆](guides/context-memory.md) |
| 做长任务和团队交付监督 | [进展监督与 TeamHarness](guides/supervision-teams.md) |
| 选业务试点 | [应用地图](applications/README.md)、[客服](applications/customer-support.md)、[知识助手](applications/knowledge-assistant.md)、[研发与数据运营](applications/engineering-data.md) |
| 定评测口径、上线与回退 | [评测与实测证据](evaluation.md)、[运行与治理](operations.md) |
| 查看本轮构建与验收 | [验证记录](implementation-validation.md) |
| 拆任务、进入研发 | [实施路线和验收](implementation-plan.md) |
| 从GitHub案例选择实施工作项 | [案例与规划映射](applications/github-cases.md) |
| 找开源参考与差异 | [参考项目及来源](references.md) |

## 三条使用路线

1. **应用先行**：业务服务 → JevClient → 原子问题 → 业务规则。先做工单分流、资料筛选和输出审核，不需要 Agent 或 Harness。
2. **框架增强**：沿用现有 Middleware，在明确的调用边界插入判断；先影子运行，再接管一个低影响动作。
3. **平台运营**：把定义版本、评测、轨迹和策略与AgentScope Service 的运行与评估链路 关联。当前已接入 Service 用途配置与判断事件；业务闭环和生产运行验收仍需逐项推进。

## 首批试点

选择“客服意图与条件提取”“知识助手证据筛选”“研发任务交付检查”三个端到端试点；共享客户端、判定状态、观测与评测格式。先构建跨场景可验证的纵向流程，再沉淀公共抽象，避免先造一个无人使用的中心服务。

文档结构参考 spring-ai-typesafe 的概念、客户端、Judge、Guardrail、RAG、Tool Search、组合模式与示例分层；组件设计按本仓库 Reactor/Middleware/RuntimeContext 实际机制重新组织。[参考依据](references.md)

本目录作为仓库内研发专题阅读，暂不加入正式双语产品导航；未修改现有 v1/v2 文档结构。未来功能验收后再将稳定使用指南迁入对应站点版本。

## 文档与示例验证

本轮实现与验证以[2026-09-25 验证记录](implementation-validation.md)为准，下方迁移记录仅供历史追溯。

安装站点开发依赖后，从仓库根目录运行：

```bash
node docs/jev/check.mjs
```

该命令检查本专题MDX语法、标题、仓库内链接、JSON及合成样本标签，不调用模型。正式站点在`.mintignore`中排除本目录，避免待实施设计作为产品使用页面发布；这不影响仓库内阅读。

迁移前验证：18篇文档检查、站点测试与构建校验、Jev模块49项测试通过；Core共2443项测试，9项跳过、无失败。客户端示例使用JDK 17编译通过，未执行其计费main方法。迁移时尚未实施新的Judge/RAG/监督组件，也未部署服务；详细研发验收仍按[实施计划](implementation-plan.md)推进。

当前工作目录为 `~/agentscope-3/agentscope-java`，分支为 `main`。2026-09-24迁移时核验了全部25个交付文件和文档所引用的关键源码；源码与迁移前基线一致。上述Java测试与示例编译是迁移前记录，不表示在新目录重复执行过构建。后续JEV改造以本目录为准。
