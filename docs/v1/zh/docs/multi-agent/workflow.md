# 自定义工作流（Custom Workflow）

在**自定义工作流**模式中，你使用 **Spring AI Alibaba StateGraph** 定义自己的执行流程。你可以完全控制图结构——顺序步骤、条件分支、循环与并行执行。节点可以是**确定性**的（如向量检索、数据库查询）、**基于 LLM** 的（如改写、分类）或**智能体**（带工具的 Agent）。节点之间通过共享状态 Map 传递数据，并可为每个 key 配置策略（覆盖或追加）。

当标准模式（Pipeline、Routing、Subagents 等）不适用、需要将确定性逻辑与智能体行为混合、或流程需要多阶段且对执行顺序有明确要求时，适合采用该模式。

## 特点

- **图结构完全自定**：节点与边由你定义，框架不固定拓扑。
- **混合确定性与智能体步骤**：部分节点不调用 LLM（如 retrieve、list_tables），部分节点调用模型或带工具的 Agent。
- **显式状态**：每个节点读写状态（如 `question`、`rewritten_query`、`documents`、`messages`）；通过 Key 策略（replace / append）控制合并方式。
- **可组合**：可将其他模式作为节点嵌入，例如将 AgentScopeAgent 或 Pipeline 作为图中的单个节点。

## 何时使用

在以下情况可考虑自定义工作流：

- 需要**多阶段流水线**，且不是简单的“同构智能体顺序执行”（例如：rewrite → retrieve → agent，或 list_tables → get_schema → generate_query）。
- 希望将**非 LLM 步骤**（检索、数据库、API）与 LLM/智能体步骤按固定顺序组合。
- 标准模式（Pipeline、Routing、Subagents、Supervisor）无法表达你的流程，或需要**条件分支、循环**等更灵活的控制。

## 实现方式

项目使用 **Spring AI Alibaba** 的 `StateGraph` 与 `CompiledGraph`。主要步骤：

1. **定义状态与策略**：创建 `StateGraph`，指定名称与 `Map<String, KeyStrategy>` 的提供者。对单值 key（如 `question`、`rewritten_query`）使用 `ReplaceStrategy`，对列表或消息历史（如 `messages`）使用 `AppendStrategy`。
2. **添加节点**：节点可以是：
   - **函数节点**：实现 `NodeAction`（同步或通过 `node_async(...)` 异步）。接收 `OverAllState`，返回 `Map<String, Object>` 作为状态更新。可以是纯逻辑（如向量检索）或一次 LLM 调用（如改写）。
   - **智能体节点**：`AgentScopeAgent.asNode()`。智能体从状态获取输入（如通过 `instruction` 的 `{input}` 或 state 内容），并返回更新（如 `messages`）。
3. **添加边**：`addEdge(START, "first_node")`、`addEdge("node_a", "node_b")`、`addEdge("last_node", END)`。若需条件路由，可使用条件边（下文示例未展开）。
4. **编译与调用**：`graph.compile()` 得到 `CompiledGraph`；传入初始状态 Map 调用，从返回结果中读取最终状态（如 `messages`、`answer`）。

**示例：RAG 工作流（概念）**

```
START → rewrite → retrieve → prepare_agent → rag_agent → END
```

- **rewrite**：LLM 改写查询以提升检索效果（使用 Model 的函数节点）。
- **retrieve**：向量相似度检索，无 LLM（使用 Knowledge 的函数节点）。
- **prepare_agent**：将上下文与问题格式化为 prompt（确定性函数节点）。
- **rag_agent**：AgentScopeAgent（ReActAgent），带上下文与可选工具（如 `get_latest_news`）。

**示例：SQL 工作流（概念）**

```
START → list_tables → call_get_schema → get_schema → generate_query → END
```

- **list_tables**：列出数据库表（使用 SqlTools 的函数节点）。
- **call_get_schema**：LLM 决定获取哪些表的 schema（函数节点）。
- **get_schema**：执行 schema 查询（函数节点）。
- **generate_query**：AgentScopeAgent，具备 `sql_db_list_tables`、`sql_db_schema`、`sql_db_query` 工具，生成并执行 SELECT。

## 示例项目

**路径**：`agentscope-examples/multiagent-patterns/workflow/`

| 包名       | 流程 | 说明 |
|------------|------|------|
| **ragagent** | Query → Rewrite → Retrieve → Prepare → Agent → Response | RAG：改写查询、向量检索，再由 ReActAgent 结合上下文与 `get_latest_news` 工具回答。使用 AgentScope Model、Knowledge（embedding + store）与 AgentScopeAgent。 |
| **sqlagent** | START → list_tables → get_schema → generate_query → END | SQL：列出表、获取相关表 schema，再由 ReActAgent 使用 SQL 工具（list_tables、schema、query）生成并执行查询。使用 H2 内存库与 AgentScopeAgent。 |

**构建与运行**（在仓库根目录）：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/workflow -am -B package -DskipTests
```

**运行 RAG 工作流**（可选启动时跑一次演示）：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/workflow spring-boot:run \
  -Dspring-boot.run.arguments="--workflow.rag.enabled=true --workflow.runner.enabled=true"
```

**运行 SQL 工作流**（可选启动时跑一次演示）：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/workflow spring-boot:run \
  -Dspring-boot.run.arguments="--workflow.sql.enabled=true --workflow.runner.enabled=true"
```

**配置**：

- `workflow.rag.enabled` – 启用 RAG 工作流 Bean（默认 `false`）。
- `workflow.sql.enabled` – 启用 SQL 工作流 Bean（示例中默认 `true`）。
- `workflow.runner.enabled` – 为 `true` 时，启动时执行一次演示（示例中默认 `true`，需与上述其一搭配）。
- **DashScope API Key**：`AI_DASHSCOPE_API_KEY` 或 `spring.ai.dashscope.api-key`（RAG 与 SQL 均需；RAG 还需配置 embedding 模型）。

## 与其他模式的关系

- **Pipeline**：Pipeline 使用**流式智能体**（SequentialAgent、ParallelAgent、LoopAgent）和固定拓扑（顺序、扇出、循环）。自定义工作流使用 **StateGraph** 与任意节点/边，可手动实现类似流水线并加入条件或“确定性 + 智能体”混合步骤。
- **Routing**：Routing 为“分类 → 专家 → 合并”。在自定义工作流中可用自己的图实现（如 router 节点 → 分支 → 专家 → 合并节点）。
- **Handoffs**：Handoffs 通过状态（如 `active_agent`）在智能体节点间路由。自定义工作流也可用条件边与状态实现；Handoffs 是专门表达“谁负责当前对话”的交接模式。

实现细节与代码见示例目录 `agentscope-examples/multiagent-patterns/workflow/` 及 RAG/SQL 配置类（`RagAgentConfig`、`SqlAgentConfig`）。

## 相关文档

- [Pipeline](pipeline.md) - 预定义流程（顺序、并行、循环）
- [Routing](routing.md) - 分类 → 专家 → 综合
- [概览](overview.md) - 多智能体模式总览
