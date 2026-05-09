# Routing（路由）

在 **Router** 模式中，路由步骤对输入进行分类并分发给专职智能体。可以调用零个或多个专家（例如并行），再将结果合成为一个回答。适用于存在多个**垂直领域**的场景——每个领域有独立的知识与对应智能体（如 GitHub、Notion、Slack）。

在 Routing 模式中，路由器对查询进行分解，专家智能体被调用（单个或并行），最后得到组合答案。

## 概述

核心特点：

- **分类步骤**：路由器（如 LLM 或规则分类器）决定调用哪些专家以及向每个专家发送什么子查询。
- **专家智能体**：每个垂直领域有独立智能体（如 GitHub 专家、Notion 专家、Slack 专家），以 **AgentScopeAgent** 与 AgentScope 工具实现。
- **合成**：将专家返回的原始结果合并为一个连贯答案（例如通过一次最终 LLM 调用）。

当输入类别清晰、需要查询多个来源（可并行）并希望得到单一组合回答时，可使用路由模式。

## 架构

```
查询 → Router（分类）→ [智能体 A] → 合成 → 组合答案
                ↘ [智能体 B] ↗
                ↘ [智能体 C] ↗
```

在 AgentScope 示例中提供两种实现方式：**Simple**（单一路由智能体 + 服务层合成）与 **Graph**（StateGraph 包装预处理/路由/后处理）。两种方式均使用 AgentScope 的 `DashScopeChatModel` 和 **AgentScopeAgent** 作为各专家（GitHub、Notion、Slack），并配以桩工具（`GitHubStubTools`、`NotionStubTools`、`SlackStubTools`）。

### Simple 与 Graph 对比

| 维度 | Simple | Graph |
|------|--------|--------|
| **入口** | `RouterService.run(query)`，内部直接调用 `AgentScopeRoutingAgent.invoke(query)` | `RoutingGraphService.run(query)`，内部调用 `CompiledGraph.invoke(Map.of("input", query))` |
| **流程** | 一次 invoke 完成：分类 → 并行专家 → 框架内 merge；再由 RouterService 可选地做一次 LLM 合成 | 图：START → preprocess → **routing（子图）** → postprocess → END；routing 内部仍是分类 → 并行专家 → merge |
| **状态** | 由 `AgentScopeRoutingAgent` 内部管理；RouterService 从返回的 `OverAllState` 中解析 `xxx_key`、`xxx_input`、`RoutingMergeNode.DEFAULT_MERGED_OUTPUT_KEY` | 显式 `StateGraph` 状态键：`input`、`query`、`messages`、`preprocess_metadata`、`merged_result`、`final_answer`、`postprocess_metadata`、各专家 `xxx_key`；每键对应 `KeyStrategy`（Replace/Append） |
| **扩展性** | 无图结构，无法在“路由前后”插入自定义节点 | 可在 preprocess / postprocess 插入任意逻辑（校验、埋点、格式化）；可增加节点或条件边 |
| **返回值** | `RouterResult(query, classifications, results, finalAnswer)`，含分类列表与各专家原始输出 | `RoutingGraphResult(query, state, finalAnswer)`，含完整图状态，便于调试或下游使用 |

---

## 实现（Simple 变体）

### 设计要点

- **单一调用完成路由**：业务只调 `RouterService.run(query)`；内部用 **AgentScopeRoutingAgent** 一次 `invoke(query)` 完成分类、并行专家、框架内 merge。
- **合成可选**：若路由框架已写入 `RoutingMergeNode.DEFAULT_MERGED_OUTPUT_KEY`，则直接用作最终答案；否则由 **RouterService** 用同一 `Model` 再跑一次 LLM，将各专家结果合成为一条回复。

### 代码结构（包 `io.agentscope.examples.routing.simple`）

| 类 | 职责 |
|----|------|
| **RoutingConfig** | 定义 `Model`、三个专家 **AgentScopeAgent**（github、notion、slack）、**AgentScopeRoutingAgent**（router）、**RouterService** |
| **RouterService** | `run(query)`：调用 router.invoke → 从 state 收集 classifications / 各专家输出 → 取 merged 或调用 `synthesize()` → 返回 **RouterResult** |
| **Classification** | 记录一次路由决策：`source`（专家名）、`query`（发给该专家的子查询） |
| **AgentOutput** | 记录单个专家输出：`source`、`result`（文本） |
| **RoutingRunner** | 当 `routing.runner.enabled=true` 时启动执行一次示例查询并打日志 |

### Bean 与约定

1. **Model**：一个 `Model` Bean（如 `DashScopeChatModel`），供路由与所有专家共用。
2. **专家 AgentScopeAgent**：每个领域一个，例如：
   - `name` / `description`：供路由分类时识别；
   - **instruction**：模板中含占位符 `{agentName_input}`（如 `{github_input}`），由路由节点在运行时填入子查询；
   - **outputKey**：该专家结果写入状态的键，如 `github_key`、`notion_key`、`slack_key`；
   - **Toolkit**：注册该领域的桩工具（如 `GitHubStubTools` 的 `search_code`、`search_issues`）。
3. **AgentScopeRoutingAgent**：`AgentScopeRoutingAgent.builder().model(...).subAgents(List.of(githubAgent, notionAgent, slackAgent)).build()`；内部实现分类 + 并行调用专家 + 将结果写入各 `outputKey` 及 merge 键。
4. **RouterService**：持有 `Model` 与 `AgentScopeRoutingAgent`；`run(query)` 后若需要合成，则用 `Model` 将各专家结果格式化为一段 prompt 再调用一次 chat，得到 `finalAnswer`。

### RouterService.run 逻辑（简要）

1. `routerAgent.invoke(query)` → 得到 `OverAllState`。
2. 遍历 `github_key`、`notion_key`、`slack_key`：若 state 中有该 key，则从 state 取 `agentName_input` 与专家输出，构造 `Classification` 与 `AgentOutput` 列表。
3. 若 state 中存在 `RoutingMergeNode.DEFAULT_MERGED_OUTPUT_KEY`，则将其内容作为 `finalAnswer`；否则调用 `synthesize(query, results)`（同一 Model，系统提示为“合并多源结果回答原问题”），返回值作为 `finalAnswer`。
4. 返回 `RouterResult(query, classifications, results, finalAnswer)`。

---

## 实现（Graph 变体）

### 设计要点

- **路由作为子图嵌入**：整体是 **StateGraph**，其中「路由」不是单个 Bean 调用，而是 **AgentScopeRoutingAgent.getAndCompileGraph()** 返回的已编译子图作为一节节点；子图内部仍是分类 → 并行专家 → merge。
- **显式预处理与后处理**：在路由节点前后增加 **PreprocessNode** 与 **PostprocessNode**，用于校验、规范化、埋点、格式化等，状态在整图上流转，便于扩展和观测。

### 代码结构（包 `io.agentscope.examples.routing.graph`）

| 类 | 职责 |
|----|------|
| **RoutingGraphConfig** | 定义三个专家 **AgentScopeAgent**、**AgentScopeRoutingAgent**；构建 **StateGraph**（preprocess → routing → postprocess），定义各状态键的 **KeyStrategy**；暴露 **CompiledGraph** 与 **RoutingGraphService** |
| **PreprocessNode** | `NodeAction`：从 state 取 `input`/`query` → 校验非空、长度 ≥3 → 规范化（trim、截断 2000 字）→ 写入 `input`、`messages`、`preprocess_metadata`（含 traceId、timestamp） |
| **PostprocessNode** | `NodeAction`：从 state 取 `RoutingMergeNode.DEFAULT_MERGED_OUTPUT_KEY` 与 `preprocess_metadata` → 格式化为带 traceId/时间戳的 `final_answer` → 写入 `postprocess_metadata` |
| **RoutingGraphService** | `run(query)`：`invoke(Map.of("input", query))` → 从 state 取 `final_answer`（若无则取 `merged_result`）→ 返回 **RoutingGraphResult(query, state, finalAnswer)** |
| **RoutingGraphRunner** | 当 `routing-graph.runner.enabled=true` 时执行一次示例查询并打日志 |

### 图与状态键

- **StateGraph**：`new StateGraph("routing_graph", keyFactory)`，边为 `START → preprocess → routing → postprocess → END`。
- **keyFactory**：为 `input`、`query`、`messages`、`preprocess_metadata`、`merged_result`、`final_answer`、`postprocess_metadata` 以及 `github_key`、`notion_key`、`slack_key` 等配置策略（多为 `ReplaceStrategy`，`messages` 为 `AppendStrategy(false)`）。
- **preprocess 节点**：`node_async(new PreprocessNode())`，纯函数节点，无 LLM；输出供 routing 子图消费。
- **routing 节点**：`routerAgent.getAndCompileGraph()`，即把「分类 + 并行专家 + merge」整段逻辑作为子图挂到主图的一节，子图输出写入 `merged_result`（及各 `xxx_key`）。
- **postprocess 节点**：`node_async(new PostprocessNode())`，读取 merge 结果与预处理元数据，写出 `final_answer` 与 `postprocess_metadata`。

### PreprocessNode / PostprocessNode 与 Simple 的区别

- **Simple**：无独立预处理；若需要“再合成”，仅在 **RouterService** 内用 Model 做一次调用，无统一的状态键或 trace 元数据。
- **Graph**：预处理集中做校验与规范化，并写入 `preprocess_metadata`（如 traceId）；后处理集中做最终格式与日志，并写入 `postprocess_metadata`；整条链路状态一致，便于扩展（如加条件分支、额外节点）。

---

## 示例项目

**路径**：`agentscope-examples/multiagent-patterns/routing/`

- **Simple**：入口为 `RouterService.run(query)`；流程为分类 → 并行专家 →（框架 merge）→ 可选 RouterService 合成。设置 `routing.runner.enabled=true` 可在启动时运行 Simple 演示（见 **RoutingRunner**）。
- **Graph**：入口为 `RoutingGraphService.run(query)`；流程为预处理 → 路由子图 → 后处理。设置 `routing-graph.runner.enabled=true` 可在启动时运行 Graph 演示（见 **RoutingGraphRunner**）。

**构建与运行**（在仓库根目录）：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/routing -am -B package -DskipTests
./mvnw -pl agentscope-examples/multiagent-patterns/routing spring-boot:run
```

**仅运行 Simple 演示**：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/routing spring-boot:run \
  -Dspring-boot.run.arguments="--routing.runner.enabled=true"
```

**仅运行 Graph 演示**：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/routing spring-boot:run \
  -Dspring-boot.run.arguments="--routing-graph.runner.enabled=true"
```

**配置**：需设置 `AI_DASHSCOPE_API_KEY`（或 `spring.ai.dashscope.api-key`）供 DashScope 模型与专家调用。

## 相关文档

- [Pipeline](./pipeline.md) - 顺序与并行智能体组合
- [MsgHub](../task/msghub.md) - 多智能体消息广播
- [Agent as Tool](../task/agent-as-tool.md) - 将智能体注册为工具
