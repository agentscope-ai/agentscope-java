# Custom Workflow

In the **custom workflow** pattern, you define your own execution flow using **Spring AI Alibaba StateGraph**. You have full control over the graph structure—sequential steps, conditional branches, loops, and parallel execution. Nodes can be **deterministic** (e.g. vector search, DB query), **LLM-based** (e.g. rewrite, classify), or **agentic** (an agent with tools). State is passed between nodes via a shared state map with configurable strategies (replace, append).

This pattern is useful when standard patterns (Pipeline, Routing, Subagents, etc.) do not fit, when you need to mix deterministic logic with agentic behavior, or when your use case requires multi-stage processing with explicit control over the flow.

## Key characteristics

- **Full control over graph structure**: You define nodes and edges; the framework does not impose a fixed topology.
- **Mix deterministic and agentic steps**: Some nodes run without an LLM (e.g. retrieve, list_tables); others call a model or an agent with tools.
- **Explicit state**: Each node reads and updates state (e.g. `question`, `rewritten_query`, `documents`, `messages`). Key strategies (replace vs append) control how state is merged.
- **Composable**: You can use other patterns as nodes—for example, an AgentScopeAgent (or a Pipeline) as a single node in the graph.

## When to use

Consider a custom workflow when:

- You need a **multi-stage pipeline** that is not a simple sequence of identical agents (e.g. rewrite → retrieve → agent, or list_tables → get_schema → generate_query).
- You want to **combine non-LLM steps** (retrieval, DB, APIs) with LLM or agent steps in a fixed order.
- Standard patterns (Pipeline, Routing, Subagents, Supervisor) do not match your flow, or you need **conditional branches or loops** that those patterns do not express naturally.

## Implementation

The project uses **Spring AI Alibaba** `StateGraph` and `CompiledGraph`. Core steps:

1. **Define state and strategies**: Create a `StateGraph` with a name and a supplier of `Map<String, KeyStrategy>`. Use `ReplaceStrategy` for single-value keys (e.g. `question`, `rewritten_query`) and `AppendStrategy` for lists or message history (e.g. `messages`).
2. **Add nodes**: Each node is either:
   - A **function node**: `NodeAction` (sync or async via `node_async(...)`). It receives `OverAllState`, returns a `Map<String, Object>` of state updates. Can be pure logic (e.g. vector search) or an ad-hoc LLM call (e.g. rewrite).
   - An **agent node**: `AgentScopeAgent.asNode()`. The agent receives state (e.g. via `instruction` with `{input}` or from state contents) and returns updates (e.g. `messages`).
3. **Add edges**: `addEdge(START, "first_node")`, `addEdge("node_a", "node_b")`, `addEdge("last_node", END)`. For conditional routing you use conditional edges (not shown in the examples below).
4. **Compile and invoke**: `graph.compile()` returns a `CompiledGraph`; you invoke it with an initial state map and read the final state (e.g. `messages`, `answer`) from the result.

**Example: RAG workflow (conceptual)**

```
START → rewrite → retrieve → prepare_agent → rag_agent → END
```

- **rewrite**: LLM rewrites the query for better retrieval (function node with Model).
- **retrieve**: Vector similarity search, no LLM (function node using Knowledge).
- **prepare_agent**: Formats context and question into a prompt (deterministic function node).
- **rag_agent**: AgentScopeAgent (ReActAgent) with context and optional tools (e.g. `get_latest_news`).

**Example: SQL workflow (conceptual)**

```
START → list_tables → call_get_schema → get_schema → generate_query → END
```

- **list_tables**: Lists DB tables (function node using SqlTools).
- **call_get_schema**: LLM decides which table(s) to get schema for (function node).
- **get_schema**: Executes schema lookup (function node).
- **generate_query**: AgentScopeAgent with `sql_db_list_tables`, `sql_db_schema`, `sql_db_query` tools; generates and runs SELECT.

## Example project

**Location**: `agentscope-examples/multiagent-patterns/workflow/`

| Package   | Flow | Description |
|-----------|------|-------------|
| **ragagent** | Query → Rewrite → Retrieve → Prepare → Agent → Response | RAG: rewrite query, vector retrieve, then ReActAgent with context and `get_latest_news` tool. Uses AgentScope Model, Knowledge (embedding + store), and AgentScopeAgent. |
| **sqlagent** | START → list_tables → get_schema → generate_query → END | SQL: list tables, get schema for relevant tables, then ReActAgent with SQL tools (list_tables, schema, query). Uses H2 in-memory and AgentScopeAgent. |

**Build and run** (from repo root):

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/workflow -am -B package -DskipTests
```

**Run RAG workflow** (with optional demo on startup):

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/workflow spring-boot:run \
  -Dspring-boot.run.arguments="--workflow.rag.enabled=true --workflow.runner.enabled=true"
```

**Run SQL workflow** (with optional demo on startup):

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/workflow spring-boot:run \
  -Dspring-boot.run.arguments="--workflow.sql.enabled=true --workflow.runner.enabled=true"
```

**Configuration**:

- `workflow.rag.enabled` – Enable RAG workflow beans.
- `workflow.sql.enabled` – Enable SQL workflow beans.
- `workflow.runner.enabled` – When `true`, run a one-shot demo on startup (use with one of the above).
- **DashScope API key**: `AI_DASHSCOPE_API_KEY` or `spring.ai.dashscope.api-key` (required for RAG and SQL; RAG also needs an embedding model).

## Custom workflow vs other patterns

- **Pipeline**: Pipeline uses **flow agents** (SequentialAgent, ParallelAgent, LoopAgent) with a fixed topology (sequence, fan-out, loop). Custom workflow uses **StateGraph** and arbitrary nodes/edges; you can implement pipeline-like flows manually and add conditional or mixed deterministic/agentic steps.
- **Routing**: Routing is classify → specialist(s) → merge, often with a single router node. In a custom workflow you could implement the same with your own graph (e.g. router node → branch → specialists → merge node).
- **Handoffs**: Handoffs use state (e.g. `active_agent`) to route between agent nodes. Custom workflow can do the same with conditional edges and state; Handoffs is a dedicated pattern for “who owns the conversation” transitions.

For implementation details and code, see the workflow example (`agentscope-examples/multiagent-patterns/workflow/`) and the RAG/SQL config classes (`RagAgentConfig`, `SqlAgentConfig`).

## Related Documentation

- [Pipeline](pipeline.md) - Predefined flows (sequential, parallel, loop)
- [Routing](routing.md) - Classify → specialists → synthesize
- [Overview](overview.md) - Multi-agent patterns summary
