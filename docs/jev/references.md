---
title: "参考项目、源码位置与证据边界"
---

# 参考项目、源码位置与证据边界

核查日期：2026-09-24。外部项目提供机制参考，本文不将作者演示或社区成绩当作本项目生产验收。文档为独立整理，未复制第三方实现；后续移植源码须保留相应许可证和声明。

## Spring AI TypeSafe

固定参考提交：`0eb01f54a0216d07d2e062eb1df1380ae6077d8a`，Apache-2.0。已查看文档导航以及Judge、SelfRefine、Guardrail、DocumentFilter、ToolIndex源码。当前README标记的依赖示例为SNAPSHOT；不据此假定稳定发布接口。

| 借鉴点 | 原项目资料 | 本专题落点与差异 |
| --- | --- | --- |
| 客户端与集成分层 | [模块组织](https://github.com/spring-ai-community/spring-ai-typesafe/tree/0eb01f54a0216d07d2e062eb1df1380ae6077d8a) | 复用本仓库JevClient，沿用HttpTransport/Reactor，不引入另一套Spring客户端 |
| 原子问题与规则组合 | [JevJudge](https://github.com/spring-ai-community/spring-ai-typesafe/blob/0eb01f54a0216d07d2e062eb1df1380ae6077d8a/docs/judge/JevJudge.md) | 独立应用Judge先行，框架适配随后 |
| 缺陷反馈与有限重试 | [SelfRefine](https://github.com/spring-ai-community/spring-ai-typesafe/blob/0eb01f54a0216d07d2e062eb1df1380ae6077d8a/docs/judge/JevSelfRefineAdvisor.md) | 区分重新生成答案与重放有副作用的Agent调用 |
| 输入/输出护栏 | [Guardrail](https://github.com/spring-ai-community/spring-ai-typesafe/blob/0eb01f54a0216d07d2e062eb1df1380ae6077d8a/docs/guardrails/JevGuardrailAdvisor.md) | 结合本项目权限和流式事件，不照搬Advisor顺序 |
| 过滤与排序分工 | [Filter](https://github.com/spring-ai-community/spring-ai-typesafe/blob/0eb01f54a0216d07d2e062eb1df1380ae6077d8a/docs/rag/JevDocumentFilter.md)、[Reranker](https://github.com/spring-ai-community/spring-ai-typesafe/blob/0eb01f54a0216d07d2e062eb1df1380ae6077d8a/docs/rag/JevDocumentReranker.md) | 应用检索后处理，区分UNSCORED与无关 |
| 适用性与候选排名分开 | [ToolIndex](https://github.com/spring-ai-community/spring-ai-typesafe/blob/0eb01f54a0216d07d2e062eb1df1380ae6077d8a/docs/toolsearch/JevToolIndex.md) | 改进当前选空透传语义，保留执行时权限校验 |
| 用途阈值与复核 | [ConfidenceGate](https://github.com/spring-ai-community/spring-ai-typesafe/blob/0eb01f54a0216d07d2e062eb1df1380ae6077d8a/docs/patterns/JevConfidenceGate.md) | ACCEPT/REJECT/ABSTAIN/ERROR与实际授权分开 |
| 稳定性实验 | [Consistency](https://github.com/spring-ai-community/spring-ai-typesafe/blob/0eb01f54a0216d07d2e062eb1df1380ae6077d8a/docs/patterns/JevConsistency.md) | 重复采样仅诊断；随机uid不能作为独立抽样证明 |

## 本仓库源码索引

以下链接面向仓库内阅读；基线commit为`8d9bec688a002537df93a0c288e225c10078fe43`。类存在与功能生产可用是不同结论。

- [JevClient](../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/JevClient.java)：传输、默认模型、超时重试与结构校验。
- [工具筛选示例](../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/example/JevToolSelectionMiddleware.java)：onReasoning、候选分块及选空透传。
- [模型路由示例](../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/example/JevModelRouterMiddleware.java)：调用内粘性路由。
- [Auto预检示例](../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/example/JevAutoModeMiddleware.java)：guardedTools与拒绝结果。
- [MiddlewareBase](../../agentscope-core/src/main/java/io/agentscope/core/middleware/MiddlewareBase.java)、[ActingInput](../../agentscope-core/src/main/java/io/agentscope/core/middleware/ActingInput.java)：扩展点、顺序和工具列表。
- [RuntimeContext](../../agentscope-core/src/main/java/io/agentscope/core/agent/RuntimeContext.java)：调用范围属性和AgentState解析。
- [CompactionMiddleware](../../agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/CompactionMiddleware.java)、[MemoryFlushMiddleware](../../agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/MemoryFlushMiddleware.java)：上下文与记忆路径。
- [TeamsMiddleware](../../agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/TeamsMiddleware.java)、[TeamClient](../../agentscope-harness/src/main/java/io/agentscope/harness/agent/team/TeamClient.java)：协作能力入口。
- [旧Knowledge接口](../../agentscope-core/src/main/java/io/agentscope/core/rag/Knowledge.java)：废弃标记与应用层集成建议。
- [已有Jev介绍文章](../v2/zh/blogs/jev-structured-decision-for-agents.md)：背景阅读；实施行为以源码为准。

## 其他研究来源

| 项目 | 本方案参考的机制 | 证据限制 |
| --- | --- | --- |
| [System One](https://docs.typesafe.ai/api) | typed questions、批量共享state、结构化概率 | API格式不是业务正确率保证 |
| [JevBench Hard](https://github.com/fstandhartinger/jevbench/blob/2fa63fa3226cb369795525ed011800f57dcbd894/datasets/HARD-TIER.md) | 复杂规则、长材料、概率与判分定义 | 只实测111公开题，未获得封闭题 |
| [jevals](https://github.com/openlayer-ai/jevals) | 同一评估定义用于离线与在线 | 适配声明不等于各后端已生产验证 |
| [Foreman](https://github.com/thruwire/foreman) | 进展、证据和验证触发 | 架构实验，不外推自动监督收益 |
| [fast-jev-compaction](https://github.com/tamaratran/fast-jev-compaction) | 保留、卸载和移除候选 | 宿主插件机制不能直接移植，误删需端到端评测 |
| [jev-ultrafast](https://github.com/browser-use/jev-ultrafast) | 可见元素转有限候选，再选动作 | 有限演示，不等于通用浏览器Agent成功率 |
| [jev-review](https://github.com/devagrawal09/jev-review) | 分阶段风险和证据筛查 | 结果作为审查线索 |
| [Jevonian](https://github.com/xinyao27/jevonian) | 候选模型约束和路由账本 | 不能引用未复现的节省率 |
| [Open-Jev](https://github.com/Zefan-Cai/Open-Jev)、[JevK5](https://github.com/allebee/jevk5) | 独立后端与类型化接口适配 | 独立实现，权重、语言、接口及阈值须重验 |
| [Managed Agents Outcomes](https://platform.claude.com/docs/en/managed-agents/define-outcomes) | 产物标准与独立验收上下文 | 产品参考，不假定可以替换内置grader |

除固定commit的来源外，其余链接为此前调研的可变阅读入口；真正移植或实验前必须记录新commit、模型revision和部署状态。此前研究中的自有AgentCore在本项目统一映射到AgentScope Service，不引入同名外部产品。
