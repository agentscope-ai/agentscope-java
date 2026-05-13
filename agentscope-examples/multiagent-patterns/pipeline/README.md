# Pipeline Examples

Pipeline examples use **Spring AI Alibaba** flow agents (**SequentialAgent**, **ParallelAgent**, **LoopAgent**) with **AgentScopeAgent** sub-agents and **AgentScope** DashScopeChatModel (`Model`).

## Prerequisites

- JDK 17+
- Maven 3.6+
- **DashScope API key**: `export AI_DASHSCOPE_API_KEY=your-key` or set `spring.ai.dashscope.api-key` in `application.yml`

## Agents

### 1. SequentialAgent: `sequential_sql_agent`

**Scenario:** Natural language → SQL → score.

- **SQL Generator** (AgentScopeAgent) converts natural language to MySQL SQL.
- **SQL Rater** (AgentScopeAgent) scores how well the SQL matches user intent (0–1).

Sub-agents run in sequence; each output feeds the next.

**Example prompt:** "List all orders from the last 30 days with total amount greater than 500."

---

### 2. ParallelAgent: `parallel_research_agent`

**Scenario:** One topic researched from three angles in parallel.

- **Tech Researcher** (AgentScopeAgent) – technology perspective.
- **Finance Researcher** (AgentScopeAgent) – finance/business perspective.
- **Market Researcher** (AgentScopeAgent) – industry/market perspective.

Results are merged into a single report (`research_report`).

**Example prompt:** "Research the current state of large language models."

---

### 3. LoopAgent: `loop_sql_refinement_agent`

**Scenario:** Iterative SQL refinement until quality score > 0.5.

- Inner **SequentialAgent**: SQL Generator → SQL Rater (both AgentScopeAgent).
- Loop continues until score > 0.5 or max iterations.

**Example prompt:** "Find customers who placed more than 3 orders in 2024."

## Build

From the repo root:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/pipeline -am -B package -DskipTests
```

## Run

**Default** (no test run):

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/pipeline spring-boot:run
```

**With test runner** (runs Sequential, Parallel, and Loop demos on startup):

```bash
export pipeline.runner.enabled=true
./mvnw -pl agentscope-examples/multiagent-patterns/pipeline spring-boot:run
```

Or set in `application.yml`: `pipeline.runner.enabled: true`.

## Configuration

- **`spring.ai.dashscope.api-key`** – Optional; defaults to `AI_DASHSCOPE_API_KEY` env var.
- **`pipeline.runner.enabled`** – If `true`, `PipelineCommandRunner` runs a demo for each pipeline on startup. Default: `false`.

## Project layout

```
agentscope-examples/multiagent-patterns/pipeline/
├── README.md
├── pom.xml
└── src/main/
    ├── java/.../pipeline/
    │   ├── PipelineApplication.java
    │   ├── PipelineModelConfig.java        # Model (DashScopeChatModel) bean
    │   ├── PipelineService.java            # runSequential, runParallel, runLoop
    │   ├── PipelineCommandRunner.java      # test runner (pipeline.runner.enabled)
    │   ├── PipelineRunnerConfig.java
    │   ├── sequential/
    │   │   └── SequentialPipelineConfig.java
    │   ├── parallel/
    │   │   └── ParallelPipelineConfig.java
    │   └── loop/
    │       └── LoopPipelineConfig.java
    └── resources/
        └── application.yml
```

## Using in your own code

Inject `PipelineService` and call `runSequential(input)`, `runParallel(input)`, or `runLoop(input)` to invoke the corresponding pipeline and get the result (e.g. `SequentialResult`, `ParallelResult`, `LoopResult`).
