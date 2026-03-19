# Multi-Agent Workflow Examples

This module demonstrates the **custom workflow** pattern: you define your own execution flow using **Spring AI Alibaba StateGraph** with full control over the graph—sequential steps, mixed **deterministic** (e.g. vector search, DB) and **agentic** (LLM/agent) nodes. Use it when standard patterns (Pipeline, Routing, Subagents) don’t fit or you need multi-stage processing with explicit control. See [Custom Workflow](../../../docs/en/multi-agent/workflow.md) in the docs.

**Included examples:**

- **RAG agent**: rewrite → retrieve → prepare → agent (AgentScope ReActAgent + tools).
- **SQL agent**: list_tables → get_schema → generate_query (AgentScopeAgent + SQL tools).

Both use Spring AI Alibaba StateGraph and AgentScope (Model, Knowledge, AgentScopeAgent) where applicable.

## Prerequisites

- JDK 17+
- Maven 3.6+
- **DashScope API key**: `export AI_DASHSCOPE_API_KEY=your-key`

## Packages

| Package | Description | Enable |
|---------|-------------|--------|
| **ragagent** | RAG: rewrite → retrieve → prepare → agent (AgentScope ReActAgent + Model) | `workflow.rag.enabled=true` |
| **sqlagent** | SQL: list_tables → get_schema → generate_query (AgentScopeAgent + tools) | `workflow.sql.enabled=true` |

## RAG Agent

**Flow:** Query → Rewrite → Retrieve → Prepare → Agent → Response

- Rewrite: LLM rewrites the query for better retrieval.
- Retrieve: Vector similarity search (no LLM).
- Prepare: Formats context and question into a prompt.
- Agent: ReActAgent with context; can use `get_latest_news` tool.

Requires DashScope API key and EmbeddingModel (e.g. from spring-ai-alibaba-starter-dashscope when enabled).

## SQL Agent

**Flow:** START → list_tables → get_schema → generate_query (agent) → END

- Uses H2 in-memory with a Chinook-like schema.
- Agent has tools: `sql_db_list_tables`, `sql_db_schema`, `sql_db_query` (SELECT only).

## Build

From the repo root:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/workflow -am -B package -DskipTests
```

## Run

**RAG agent** (with optional demo on startup):

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/workflow spring-boot:run \
  -Dspring-boot.run.arguments="--workflow.rag.enabled=true --workflow.runner.enabled=true"
```

**SQL agent** (with optional demo on startup):

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/workflow spring-boot:run \
  -Dspring-boot.run.arguments="--workflow.sql.enabled=true --workflow.runner.enabled=true"
```

Set `AI_DASHSCOPE_API_KEY` (or `spring.ai.dashscope.api-key` in `application.yml`).

## Configuration

- **`workflow.rag.enabled`** – Enable RAG workflow beans.
- **`workflow.sql.enabled`** – Enable SQL workflow beans.
- **`workflow.runner.enabled`** – When `true`, run a one-shot demo on startup (use with one of the above).

## Project layout

```
agentscope-examples/multiagent-patterns/workflow/
├── README.md
├── pom.xml
└── src/main/
    ├── java/.../workflow/
    │   ├── ragagent/          # RAG workflow config, service, runner
    │   └── sqlagent/          # SQL workflow config, service, runner
    └── resources/
        └── application.yml
```
