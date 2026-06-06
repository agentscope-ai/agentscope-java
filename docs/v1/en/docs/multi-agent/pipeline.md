# Pipeline

The pipeline example uses **Spring AI Alibaba** flow agents (**SequentialAgent**, **ParallelAgent**, **LoopAgent**) with **AgentScopeAgent** sub-agents and AgentScope **DashScopeChatModel** (`Model`). Each pipeline is built from ReActAgent-based AgentScopeAgents and invoked via `PipelineService`.

**Location**: `agentscope-examples/multiagent-patterns/pipeline/`

## Prerequisites

- JDK 17+
- Maven 3.6+
- **DashScope API key**: `export AI_DASHSCOPE_API_KEY=your-key` or set `spring.ai.dashscope.api-key` in `application.yml`

## Model configuration

The example uses a single `Model` bean (DashScopeChatModel) shared by all pipeline sub-agents:

```java
package com.alibaba.cloud.ai.examples.multiagents.pipeline;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PipelineModelConfig {

	@Bean
	public Model dashScopeChatModel() {
		String key = System.getenv("AI_DASHSCOPE_API_KEY");
		return DashScopeChatModel.builder()
				.apiKey(key)
				.modelName("qwen-plus")
				.build();
	}
}
```

## 1. SequentialAgent: natural language → SQL → score

**Scenario:** User describes a query in natural language. The pipeline (1) **SQL Generator** converts it to MySQL SQL, (2) **SQL Rater** scores how well the SQL matches user intent (0–1). Sub-agents run in sequence; each output feeds the next.

**Example input:** "List all orders from the last 30 days with total amount greater than 500."

### Implementation

```java
package com.alibaba.cloud.ai.examples.multiagents.pipeline.sequential;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SequentialPipelineConfig {

	private static final String SQL_GENERATOR_PROMPT = """
			You are a MySQL database expert. Given the user's natural language request, output the corresponding SQL statement.
			Only output valid MySQL SQL. Do not include explanations.
			""";

	private static final String SQL_RATER_PROMPT = """
			You are a SQL quality reviewer. Given the user's natural language request and the generated SQL,
			output a single float score between 0 and 1. The score indicates how well the SQL matches the user intent.
			Output ONLY the number, no other text. Example: 0.85
			""";

	@Bean("sequentialSqlAgent")
	public SequentialAgent sequentialSqlAgent(Model dashScopeChatModel) {
		ReActAgent.Builder sqlGenBuilder = ReActAgent.builder()
				.name("sql_generator")
				.model(dashScopeChatModel)
				.description("Converts natural language to MySQL SQL")
				.sysPrompt(SQL_GENERATOR_PROMPT)
				.memory(new InMemoryMemory());
		AgentScopeAgent sqlGenerateAgent = AgentScopeAgent.fromBuilder(sqlGenBuilder)
				.name("sql_generator")
				.description("Converts natural language to MySQL SQL")
				.instruction("{input}")
				.includeContents(false)
				.outputKey("sql")
				.build();

		ReActAgent.Builder sqlRaterBuilder = ReActAgent.builder()
				.name("sql_rater")
				.model(dashScopeChatModel)
				.description("Scores SQL against user intent")
				.sysPrompt(SQL_RATER_PROMPT)
				.memory(new InMemoryMemory());
		AgentScopeAgent sqlRatingAgent = AgentScopeAgent.fromBuilder(sqlRaterBuilder)
				.name("sql_rater")
				.description("Scores SQL against user intent")
				.instruction("Here's the generated SQL:\n {sql}.\n\n Here's the original user request:\n {input}.")
				.includeContents(false)
				.outputKey("score")
				.build();

		return SequentialAgent.builder()
				.name("sequential_sql_agent")
				.description("Natural language to SQL pipeline: generates SQL and scores its quality")
				.subAgents(List.of(sqlGenerateAgent, sqlRatingAgent))
				.build();
	}
}
```

- **SQL Generator**: `instruction("{input}")`, `outputKey("sql")` — receives user input, writes generated SQL into state key `sql`.
- **SQL Rater**: `instruction("... {sql} ... {input} ...")`, `outputKey("score")` — receives previous `sql` and original `input`, writes score into state key `score`.

## 2. ParallelAgent: multi-angle research

**Scenario:** User provides a topic; the pipeline researches it from three angles **in parallel**: technology, finance/business, and market/industry. Results are merged into a single report (`research_report`).

**Example input:** "Research the current state of large language models." (Demo uses "AI agents in enterprise software".)

### Implementation

```java
package com.alibaba.cloud.ai.examples.multiagents.pipeline.parallel;

import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ParallelPipelineConfig {

	private static final String TECH_RESEARCH_PROMPT = """
			You are a technology analyst. Research the given topic from a technology perspective.
			Provide a concise 2-3 paragraph analysis covering: key technologies, trends, and innovations.
			Focus on technical aspects only.
			""";

	private static final String FINANCE_RESEARCH_PROMPT = """
			You are a financial analyst. Research the given topic from a finance and business perspective.
			Provide a concise 2-3 paragraph analysis covering: market size, investment trends, business models.
			Focus on financial and business aspects only.
			""";

	private static final String MARKET_RESEARCH_PROMPT = """
			You are a market analyst. Research the given topic from an industry and market perspective.
			Provide a concise 2-3 paragraph analysis covering: competitive landscape, growth drivers, challenges.
			Focus on market and industry aspects only.
			""";

	@Bean("parallelResearchAgent")
	public ParallelAgent parallelResearchAgent(Model dashScopeChatModel) {
		ReActAgent.Builder techBuilder = ReActAgent.builder()
				.name("tech_researcher")
				.model(dashScopeChatModel)
				.description("Researches from technology perspective")
				.sysPrompt(TECH_RESEARCH_PROMPT)
				.memory(new InMemoryMemory());
		AgentScopeAgent techResearcher = AgentScopeAgent.fromBuilder(techBuilder)
				.name("tech_researcher")
				.description("Researches from technology perspective")
				.instruction("Research the following topic: {input}.")
				.includeContents(false)
				.outputKey("tech_analysis")
				.build();

		ReActAgent.Builder financeBuilder = ReActAgent.builder()
				.name("finance_researcher")
				.model(dashScopeChatModel)
				.description("Researches from finance perspective")
				.sysPrompt(FINANCE_RESEARCH_PROMPT)
				.memory(new InMemoryMemory());
		AgentScopeAgent financeResearcher = AgentScopeAgent.fromBuilder(financeBuilder)
				.name("finance_researcher")
				.description("Researches from finance perspective")
				.instruction("Research the following topic: {input}.")
				.includeContents(false)
				.outputKey("finance_analysis")
				.build();

		ReActAgent.Builder marketBuilder = ReActAgent.builder()
				.name("market_researcher")
				.model(dashScopeChatModel)
				.description("Researches from market perspective")
				.sysPrompt(MARKET_RESEARCH_PROMPT)
				.memory(new InMemoryMemory());
		AgentScopeAgent marketResearcher = AgentScopeAgent.fromBuilder(marketBuilder)
				.name("market_researcher")
				.description("Researches from market perspective")
				.instruction("Research the following topic: {input}.")
				.outputKey("market_analysis")
				.build();

		return ParallelAgent.builder()
				.name("parallel_research_agent")
				.description("Multi-topic research: analyzes a topic from tech, finance, and market angles in parallel")
				.subAgents(List.of(techResearcher, financeResearcher, marketResearcher))
				.mergeStrategy(new ParallelAgent.DefaultMergeStrategy())
				.mergeOutputKey("research_report")
				.maxConcurrency(3)
				.build();
	}
}
```

- Each sub-agent has `instruction("Research the following topic: {input}.")` and its own `outputKey` (`tech_analysis`, `finance_analysis`, `market_analysis`).
- **DefaultMergeStrategy** merges sub-agent outputs into one; merged result is written to **mergeOutputKey** `research_report`.

## 3. LoopAgent: SQL refinement until quality threshold

**Scenario:** Generate SQL from natural language and **iteratively refine** until the quality score exceeds 0.5. Each iteration runs an inner **SequentialAgent**: SQL Generator → SQL Rater. Loop continues until score > 0.5 or max iterations.

**Example input:** "Find customers who placed more than 3 orders in 2024."

### Implementation

`LoopPipelineConfig` builds the same SQL Generator and SQL Rater AgentScopeAgents as `SequentialPipelineConfig` (same prompts, `instruction`, `outputKey`). It then wraps them in a **SequentialAgent** and that in a **LoopAgent** with a condition-based loop strategy:

```java
SequentialAgent sqlAgent = SequentialAgent.builder()
		.name("sql_agent")
		.description("Generates SQL and scores its quality")
		.subAgents(List.of(sqlGenerateAgent, sqlRatingAgent))
		.build();

return LoopAgent.builder()
		.name("loop_sql_refinement_agent")
		.description("Iteratively refines SQL until quality score exceeds " + QUALITY_THRESHOLD)
		.subAgent(sqlAgent)
		.loopStrategy(LoopMode.condition(messages -> {
			if (messages == null || messages.isEmpty()) return false;
			String text = messages.get(messages.size() - 1).getText();
			if (text == null || text.isBlank()) return false;
			try {
				double score = Double.parseDouble(text.trim());
				return score > QUALITY_THRESHOLD;
			} catch (NumberFormatException e) {
				return false;
			}
		}))
		.build();
```

- **LoopAgent** wraps a single **SequentialAgent** (`sql_agent`) that runs SQL Generator then SQL Rater each iteration.
- **loopStrategy**: `LoopMode.condition(...)` — receives the last turn’s messages, reads the last message (rater output), parses it as a number; returns `true` when score > 0.5 to stop the loop.

## Invoking pipelines: PipelineService

`PipelineService` is wired with the three agents and exposes `runSequential`, `runParallel`, and `runLoop`. Each method invokes the corresponding agent with a string input and returns a result record.

```java
// Inject in your code
@Autowired
PipelineService pipelineService;

// Sequential: natural language → SQL → score
PipelineService.SequentialResult seq = pipelineService.runSequential(
		"List all orders from the last 30 days with total amount greater than 500");
// seq.input(), seq.sql(), seq.score()

// Parallel: one topic → merged research report
PipelineService.ParallelResult par = pipelineService.runParallel(
		"AI agents in enterprise software");
// par.input(), par.researchReport()

// Loop: SQL refinement until score > 0.5
PipelineService.LoopResult loop = pipelineService.runLoop(
		"Find customers who placed more than 3 orders in 2024");
// loop.input(), loop.sql(), loop.score()
```

**Result types:**

| Method | Return type | Keys used |
|--------|-------------|-----------|
| `runSequential(input)` | `SequentialResult(input, sql, score)` | `sql`, `score` from `OverAllState` |
| `runParallel(input)` | `ParallelResult(input, researchReport)` | `research_report` from `OverAllState` |
| `runLoop(input)` | `LoopResult(input, sql, score)` | `sql`, `score` from `OverAllState` |

Service implementation (extract of how results are read from state):

```java
public SequentialResult runSequential(String userInput) throws GraphRunnerException {
	Optional<OverAllState> resultOpt = sequentialSqlAgent.invoke(userInput);
	if (resultOpt.isEmpty()) {
		return new SequentialResult(userInput, null, null);
	}
	OverAllState state = resultOpt.get();
	String sql = extractText(state.value(SQL_KEY));
	String score = extractText(state.value(SCORE_KEY));
	return new SequentialResult(userInput, sql, score);
}
```

## Optional demo runner

When `pipeline.runner.enabled=true`, `PipelineCommandRunner` runs a demo for each pipeline on startup with sample inputs and logs the results:

```java
@Component
@ConditionalOnProperty(name = "pipeline.runner.enabled", havingValue = "true")
public class PipelineCommandRunner implements ApplicationRunner {

	private final PipelineService pipelineService;

	@Override
	public void run(ApplicationArguments args) throws Exception {
		runSequentialDemo();  // "List all orders from the last 30 days..."
		runParallelDemo();    // "AI agents in enterprise software"
		runLoopDemo();        // "Find customers who placed more than 3 orders in 2024"
	}
	// ...
}
```

## Build and run

From the repo root:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/pipeline -am -B package -DskipTests
./mvnw -pl agentscope-examples/multiagent-patterns/pipeline spring-boot:run
```

**With demo runner:**

```bash
export pipeline.runner.enabled=true
./mvnw -pl agentscope-examples/multiagent-patterns/pipeline spring-boot:run
```

Or in `application.yml`: `pipeline.runner.enabled: true`.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `spring.ai.dashscope.api-key` | (env `AI_DASHSCOPE_API_KEY`) | DashScope API key for the model |
| `pipeline.runner.enabled` | `false` | If `true`, run Sequential, Parallel, and Loop demos on startup |

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
    │   ├── PipelineCommandRunner.java      # optional demo (pipeline.runner.enabled)
    │   ├── PipelineRunnerConfig.java       # PipelineService bean
    │   ├── sequential/
    │   │   └── SequentialPipelineConfig.java
    │   ├── parallel/
    │   │   └── ParallelPipelineConfig.java
    │   └── loop/
    │       └── LoopPipelineConfig.java
    └── resources/
        └── application.yml
```

## Related Documentation

- [Routing](./routing.md) - Classify and route to specialist agents
- [MsgHub](../task/msghub.md) - Message broadcasting for multi-agent conversations
- [Handoffs](./handoffs.md) - State-driven routing and transfer between agents
- [Multi-Agent Debate](./multiagent-debate.md) - Debate workflow pattern
