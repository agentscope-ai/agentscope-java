# Routing Examples

Routing examples use **Spring AI Alibaba** with **AgentScope**: classify user query → run specialist agents (GitHub, Notion, Slack) in parallel → synthesize results. All specialist agents are **AgentScopeAgent** with **AgentScope** DashScopeChatModel (`Model`).

## Prerequisites

- JDK 17+
- Maven 3.6+
- **DashScope API key**: `export AI_DASHSCOPE_API_KEY=your-key`

## Variants

| Variant | Description | Runner property |
|--------|-------------|-----------------|
| **Simple** | `AgentScopeRoutingAgent` + `RouterService` (invoke agent, then synthesize) | `routing.runner.enabled=true` |
| **Graph** | StateGraph: preprocess → routing (LlmRoutingAgent as node) → postprocess | `routing-graph.runner.enabled=true` |

Both use the same specialist agents (GitHub, Notion, Slack) as AgentScopeAgent. Simple wraps the router in a service that adds synthesis; Graph embeds the router in a larger graph with pre/post nodes.

## Build

From the repo root:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/routing -am -B package -DskipTests
```

Or from this directory:

```bash
cd agentscope-examples/multiagent-patterns/routing
mvn -B package -DskipTests
```

## Run

**Default** (no demo):

```bash
java -jar target/routing-*.jar
# or
./mvnw -pl agentscope-examples/multiagent-patterns/routing spring-boot:run
```

**Simple demo** (one query through classify → parallel agents → synthesize):

```bash
export routing.runner.enabled=true
./mvnw -pl agentscope-examples/multiagent-patterns/routing spring-boot:run
```

**Graph demo** (preprocess → routing node → postprocess):

```bash
export routing-graph.runner.enabled=true
./mvnw -pl agentscope-examples/multiagent-patterns/routing spring-boot:run
```

## Configuration

- **`spring.ai.dashscope.api-key`** – Optional; defaults to `AI_DASHSCOPE_API_KEY` env var.
- **`routing.runner.enabled`** – If `true`, runs the simple routing demo on startup. Default: `false`.
- **`routing-graph.runner.enabled`** – If `true`, runs the graph routing demo on startup. Default: `false`.

## Project layout

```
agentscope-examples/multiagent-patterns/routing/
├── README.md
├── pom.xml
└── src/main/
    ├── java/.../routing/
    │   ├── simple/                    # AgentScopeRoutingAgent, RouterService, RoutingRunner
    │   │   ├── RoutingApplication.java
    │   │   ├── RoutingConfig.java
    │   │   ├── RouterService.java
    │   │   ├── RoutingRunner.java
    │   │   └── tools/                 # GitHub, Notion, Slack stub tools
    │   └── graph/                     # StateGraph with preprocess/postprocess
    │       ├── RoutingGraphApplication.java
    │       ├── RoutingGraphConfig.java
    │       ├── RoutingGraphService.java
    │       ├── RoutingGraphRunner.java
    │       └── node/                  # PreprocessNode, PostprocessNode
    └── resources/
        └── application.yml
```

## Using in your own code

**Simple:** Inject `RouterService` and call `run(query)`; get `RouterResult` with classifications and final answer.

**Graph:** Inject `RoutingGraphService` and call `run(query)`; get result with final answer from the graph.

See in-package READMEs under `simple/` and `graph/` for more detail.
