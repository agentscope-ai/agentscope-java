---
title: "用 JEV 构建 Agent Harness"
en_link: /dev/en/docs/harness/jev/index
---

<Note>
本页属于 dev 文档，描述当前开发版本，API 与示例可能不同于已发布的包。使用 AgentScope Java `2.0.4` 时，请参阅 [2.0 文档](/v2/zh/jev/index)。
</Note>


JEV 为 Harness 中需要语义判断的环节提供类型化结果：哪些工具适用、回答是否有依据、历史材料是否需要保留、任务是否需要验证。你可以直接调用组件，也可以注册中间件或只读工具，让判断参与 Agent 的运行流程。

## 按要解决的问题选择能力

| 能力分类 | 典型场景 | 功能入口 |
|---|---|---|
| Agent 轨迹评估与定义复用 | 检查工具使用、回答依据和需求覆盖；离线与在线复用指标 | [评审定义](/dev/zh/docs/harness/jev/guides/judge-api)、[轨迹评估](/dev/zh/docs/harness/jev/guides/trace-evaluation-api)、[自定义指标](/dev/zh/docs/harness/jev/guides/metric-definitions)、[批量评估](/dev/zh/docs/harness/jev/guides/evaluator-api) |
| 上下文治理与恢复 | 长任务中保留关键约束、缩减工具历史、恢复原始材料 | [压缩与归档](/dev/zh/docs/harness/jev/guides/context-compaction-api)、[显式交换对规划](/dev/zh/docs/harness/jev/guides/context-planner-api)、[记忆准入](/dev/zh/docs/harness/jev/guides/memory-api) |
| 长任务进展与交付监督 | 持续识别偏航、停滞、验证缺失和交付复核需求 | [持续监督](/dev/zh/docs/harness/jev/guides/supervision-api)、[接入验证证据](/dev/zh/docs/harness/jev/guides/supervision-evidence)、[单次任务复核](/dev/zh/docs/harness/jev/guides/task-review) |
| 工具选择与执行防护 | 大工具集筛选；退款、发消息等执行前检查 | [工具选择](/dev/zh/docs/harness/jev/guides/tool-selection-api)、[执行防护](/dev/zh/docs/harness/jev/guides/tool-guard-api) |
| 模型、阶段与团队路由 | 按任务复杂度、工具能力、容量与配额选择候选 | [调用级路由](/dev/zh/docs/harness/jev/guides/model-routing-api)、[显式阶段](/dev/zh/docs/harness/jev/guides/phase-routing-api)、[团队建议](/dev/zh/docs/harness/jev/guides/team-routing) |
| 检索证据与知识问答 | 过滤提示注入、保留冲突证据、重排结果、检查引用支持 | [证据处理](/dev/zh/docs/harness/jev/guides/evidence-pipeline-api)、[相关性与依据检查](/dev/zh/docs/harness/jev/guides/rag-api)、[Knowledge 适配](/dev/zh/docs/harness/jev/guides/knowledge-adapter-api) |
| 内容审核与回答修订 | 发布前审核输入输出，只修订未通过的草稿 | [内容护栏](/dev/zh/docs/harness/jev/guides/content-guardrail-api)、[Agent 回答修订](/dev/zh/docs/harness/jev/guides/answer-refinement-api)、[独立草稿管线](/dev/zh/docs/harness/jev/guides/draft-pipeline-api) |
| 代码评审与证据定位 | 对 diff 或源码快照筛选风险并定位证据区域 | [分阶段代码评审](/dev/zh/docs/harness/jev/guides/code-review-api) |
| 浏览器只读导航与验证 | 从可见链接中导航，在授权页面查找信息 | [浏览器执行](/dev/zh/docs/harness/jev/guides/browser-execution-api)、[仅生成动作建议](/dev/zh/docs/harness/jev/guides/browser-proposals) |
| 业务分类与客服评审 | 多标签分流、条件性退款诉求与回复承诺检查 | [候选分类](/dev/zh/docs/harness/jev/guides/application-api)、[客服评审](/dev/zh/docs/harness/jev/guides/support-api) |

## 用一个客服流程理解组合方式

用户说“未发货就退款”时，先用工具选择保留查询和退款工具，再用执行前防护检查退款建议是否得到当前证据支持。Agent 形成回答后，用草稿审核检查是否虚构到账或遗漏退款条件；调用结束后，再用轨迹评估统计工具使用和回答质量。

这些能力可独立启用。第一次接入可从[退款工具防护](/dev/zh/docs/harness/jev/guides/tool-guard-api)学习场景、配置和失败处理，也可以直接运行下面的离线案例，观察审核失败后仅修订草稿的过程。

## 选择接入方式

- **直接调用**：应用已经有输入、证据或草稿时，调用 Judge、评估器、证据处理器等组件，自行消费结果。
- **Harness 集成**：通过 `.middleware(...)`、`.compaction(...)` 或 Toolkit 注册工具，把判断放在对应执行环节。

JEV 判断不授予工具权限，也不证明业务操作已经成功。工具授权、ACL、参数校验、幂等及独立完成验证仍由你的应用负责。各页面分别说明建议、实际输入和执行结果的关系。

## 从离线案例开始

[运行案例](/dev/zh/docs/harness/jev/guides/agent-integration-example)无需密钥，展示检索、审核和有限修订。所有可运行源码位于 `agentscope-examples/jev`，入口包为 `io.agentscope.examples.jev`。

接入真实模型前，先了解 [Noul、Choice、Score 与状态契约](/dev/zh/docs/harness/jev/concepts)，再配置[客户端](/dev/zh/docs/harness/jev/guides/client)和[运行模式](/dev/zh/docs/harness/jev/guides/harness-runtime)。文档中的概率阈值是示例值，应使用自己的业务样本校准。
