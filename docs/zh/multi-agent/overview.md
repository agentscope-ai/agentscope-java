# 多智能体概览

多智能体系统通过协调多个专职智能体或组件来完成复杂流程。并非所有复杂任务都需要多智能体——单个智能体配合合适的工具与提示往往就够用。本文概览何时采用多智能体模式更有价值，以及 AgentScope 支持哪些模式。

## 为何使用多智能体？

在以下一种或多种需求出现时，多智能体模式会很有用：

- **上下文管理**：在不压垮模型上下文的前提下暴露专项知识。当上下文与延迟都有限时，需要按步骤或按智能体只呈现相关内容。
- **分工开发**：让不同团队负责不同能力（如技能、子智能体、专家），并在清晰边界下组合使用。
- **并行化**：对子任务并行运行专职工作者，以降低延迟。
- **结构化流程**：强制顺序（如先分类再路由，或循环直到满足条件）或按角色交接（如销售 vs 支持），单智能体难以自然保证这些约束。

当单智能体工具过多且选择不佳、任务需要深领域上下文（长提示与领域工具）、或需要顺序/状态驱动路由（如先收集信息再升级、或切换“负责”对话的智能体）时，多智能体模式尤其有价值。

## 支持的模式

AgentScope 支持以下多智能体模式，每种都有独立页面说明实现与示例。

| 模式 | 作用 | 适用场景 |
|------|------|----------|
| **[Pipeline](pipeline.md)** | 按固定流程运行智能体：**顺序**（A → B → C）、**并行**（同一输入给多个智能体再合并）、**循环**（子流程重复直到条件满足）。基于 Spring AI Alibaba flow 与 AgentScopeAgent 构建。 | 流程明确（如 NL → SQL → 打分，或一主题 → 多研究角度 → 合并报告）。 |
| **[Routing](routing.md)** | **路由器**对输入分类并转发给一个或多个专家智能体（如 GitHub、Notion、Slack）；结果合并为单一回答。 | 有明确垂直领域，希望一次完成「分类 → 专家 → 综合」，或用图实现。 |
| **[Skills](skills.md)** | **按需披露**：一个智能体只看到技能名/描述，通过工具（`read_skill`）按需加载完整技能内容（如 `SKILL.md`）。无独立子智能体进程。 | 希望一个智能体具备多种专长，但不想一次性把所有领域文本塞进上下文。 |
| **[Subagents](subagent.md)** | 中心**编排智能体**通过工具（如 Task / TaskOutput）将工作委托给**子智能体**。子智能体可用 Markdown 或代码定义；编排智能体持有对话，子智能体每次调用无状态。 | 多领域（如代码库、网络、依赖），希望一个协调者，且子智能体无需直接对用户说话。 |
| **[Supervisor](supervisor.md)** | 中心**监督者**智能体将专家智能体当作**工具**调用（每个专家一个工具，如 `schedule_event`、`manage_email`）。专家无状态；仅监督者的回复呈现给用户。 | 领域清晰（如日历、邮件），希望单一入口完成路由与结果合并。 |
| **[Handoffs](handoffs.md)** | **状态驱动路由**：工具更新状态变量（如 `active_agent`），图根据该变量路由到不同智能体节点。每个智能体可通过工具调用「交接」给另一智能体。 | 需要按角色或按顺序交接（如销售 ↔ 支持），对话过程中「当前负责」的智能体会变化。 |
| **[Multi-Agent Debate](multiagent-debate.md)** | **辩手**通过 MsgHub 交换论点；**主持人**用结构化输出评估并决定辩论何时结束。 | 需要多视角（如推理任务）以及明确的结束条件与最终答案。 |
| **[Custom Workflow](workflow.md)** | 使用 **StateGraph** 自定图结构：顺序、条件或**确定性 + 智能体**混合步骤（如 rewrite → retrieve → agent，或 list_tables → get_schema → generate_query）。节点可为函数或 AgentScopeAgent。 | 标准模式不适用；需要多阶段、显式控制或非 LLM 与 LLM/智能体步骤混合时。 |

## 如何选型


### 工作流（workflow）vs 对话（conversational）
从整体上，多智能体模式可分为 **工作流（workflow）** 与 **对话（conversational）** 两类：

- **工作流模式**：包含 [Pipeline](pipeline.md)、[Routing](routing.md)、[Custom Workflow](workflow.md)。流程在智能体或节点之间流转，每个节点都可能与用户交互。
- **对话模式**：包含 [Supervisor](supervisor.md)、[Subagents](subagent.md)、[Skills](skills.md)。智能体的决策在连续的对话上下文中进行，通常只有主智能体与用户交互并将结果输出给用户。

其余模式（如 [MsgHub](../task/msghub.md)、[Agent as Tool](../task/agent-as-tool.md)、[Handoffs](handoffs.md)、[Multi-Agent Debate](multiagent-debate.md)）可按需与上述两类组合使用。

### Routing vs Supervisor



### Skills vs Subagents/Supervisor



可作快速参考；细节与取舍见各模式页面。

| 若你需要… | 可考虑 |
|-----------|--------|
| 固定流水线（顺序、并行或循环） | [Pipeline](pipeline.md) |
| 一次分类后交给专家并合并结果 | [Routing](routing.md) |
| 通过工具在智能体间切换（如销售 ↔ 支持） | [Handoffs](handoffs.md) |
| 一个智能体多种专长、按需加载上下文 | [Skills](skills.md) |
| 一个编排智能体通过 Task 工具分发给多个子智能体 | [Subagents](subagent.md) |
| 一个监督者，每个专家一个工具（如日历、邮件） | [Supervisor](supervisor.md) |
| 多个辩手 + 主持人 + 明确结束规则 | [Multi-Agent Debate](multiagent-debate.md) |
| 自定义图（确定性 + 智能体步骤、多阶段） | [自定义工作流](workflow.md) |

**组合使用**：可以混用。例如监督者用 Agent as Tool 调用专家；子智能体编排智能体用 Skills 做按需上下文；图中某段用 Handoffs、另一段用 Routing。按流程各部分需求选择最合适的模式即可。

## 小结

- **Pipeline**：预定义流程（顺序、并行、循环）。
- **Routing**：分类 → 专家 → 综合。
- **Skills**：单智能体按需加载专项提示/内容。
- **Subagents**：一个编排智能体委托给多个任务式子智能体。
- **Supervisor**：一个智能体将专家以「一专家一工具」方式路由与合并。
- **Handoffs**：图中通过工具驱动状态切换「当前」智能体。
- **Multi-Agent Debate**：辩手 + 主持人 + 明确结束条件。
- **Custom Workflow**：自定图结构，混合确定性步骤与智能体步骤。

实现细节、代码示例与示例项目见各模式对应链接页面。
