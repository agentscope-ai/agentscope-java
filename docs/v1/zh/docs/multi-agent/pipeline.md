# Pipeline（管道）

本示例使用 **Spring AI Alibaba** 的流式智能体（**SequentialAgent**、**ParallelAgent**、**LoopAgent**）与 **AgentScopeAgent** 子智能体及 AgentScope 的 **DashScopeChatModel**（`Model`）。各管道由基于 ReActAgent 的 AgentScopeAgent 构建，并通过 `PipelineService` 调用。

## 前置条件

- JDK 17+
- Maven 3.6+
- **DashScope API Key**：`export AI_DASHSCOPE_API_KEY=your-key` 或在 `application.yml` 中设置 `spring.ai.dashscope.api-key`

## 模型配置

示例使用一个共用的 `Model` Bean（DashScopeChatModel），供所有管道子智能体使用：

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

## 1. SequentialAgent：自然语言 → SQL → 得分

**场景**：用户用自然语言描述查询。管道（1）**SQL 生成器**将其转为 MySQL SQL，（2）**SQL 评分器**评估 SQL 与用户意图的匹配度（0–1）。子智能体按顺序执行，前一环节输出作为下一环节输入。

**示例输入**：「列出过去 30 天总金额大于 500 的所有订单。」

### 实现

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

- **SQL 生成器**：`instruction("{input}")`、`outputKey("sql")` — 接收用户输入，将生成的 SQL 写入状态键 `sql`。
- **SQL 评分器**：`instruction("... {sql} ... {input} ...")`、`outputKey("score")` — 接收上一步的 `sql` 与原始 `input`，将得分写入状态键 `score`。

## 2. ParallelAgent：多角度调研

**场景**：用户给出一个主题，管道从技术、金融/商业、市场/行业三个角度**并行**调研，结果合并为一份报告（`research_report`）。

**示例输入**：「调研大语言模型的当前发展状况。」（演示中使用「AI agents in enterprise software」。）

### 实现

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

- 每个子智能体使用 `instruction("Research the following topic: {input}.")` 及各自的 `outputKey`（`tech_analysis`、`finance_analysis`、`market_analysis`）。
- **DefaultMergeStrategy** 将子智能体输出合并为一份结果，写入 **mergeOutputKey** `research_report`。

## 3. LoopAgent：SQL 迭代优化直到质量阈值

**场景**：从自然语言生成 SQL，并**迭代优化**直到质量得分超过 0.5。每次迭代执行内层 **SequentialAgent**：SQL 生成器 → SQL 评分器。循环直到得分 > 0.5 或达到最大迭代次数。

**示例输入**：「找出 2024 年下单超过 3 次的客户。」

### 实现

`LoopPipelineConfig` 中构建的 SQL 生成器与 SQL 评分器 AgentScopeAgent 与 `SequentialPipelineConfig` 相同（相同 prompt、`instruction`、`outputKey`）。然后将它们包在一个 **SequentialAgent** 中，再包在带条件循环策略的 **LoopAgent** 中：

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

- **LoopAgent** 包装一个 **SequentialAgent**（`sql_agent`），每次迭代执行 SQL 生成器再执行 SQL 评分器。
- **loopStrategy**：`LoopMode.condition(...)` — 接收上一轮消息，读取最后一条（评分器输出），解析为数字；当 score > 0.5 时返回 `true` 结束循环。

## 调用管道：PipelineService

`PipelineService` 注入三个管道智能体，对外提供 `runSequential`、`runParallel`、`runLoop`。每个方法传入字符串输入并返回对应的结果记录。

```java
// 在业务代码中注入
@Autowired
PipelineService pipelineService;

// 顺序：自然语言 → SQL → 得分
PipelineService.SequentialResult seq = pipelineService.runSequential(
		"List all orders from the last 30 days with total amount greater than 500");
// seq.input(), seq.sql(), seq.score()

// 并行：一个主题 → 合并后的调研报告
PipelineService.ParallelResult par = pipelineService.runParallel(
		"AI agents in enterprise software");
// par.input(), par.researchReport()

// 循环：SQL 优化直到得分 > 0.5
PipelineService.LoopResult loop = pipelineService.runLoop(
		"Find customers who placed more than 3 orders in 2024");
// loop.input(), loop.sql(), loop.score()
```

**返回类型：**

| 方法 | 返回类型 | 使用的状态键 |
|------|----------|--------------|
| `runSequential(input)` | `SequentialResult(input, sql, score)` | 从 `OverAllState` 读取 `sql`、`score` |
| `runParallel(input)` | `ParallelResult(input, researchReport)` | 从 `OverAllState` 读取 `research_report` |
| `runLoop(input)` | `LoopResult(input, sql, score)` | 从 `OverAllState` 读取 `sql`、`score` |

服务实现片段（如何从状态中读取结果）：

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

## 可选演示运行器

当 `pipeline.runner.enabled=true` 时，`PipelineCommandRunner` 在启动时为每个管道运行一次演示（固定示例输入并输出日志）：

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

## 构建与运行

在仓库根目录执行：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/pipeline -am -B package -DskipTests
./mvnw -pl agentscope-examples/multiagent-patterns/pipeline spring-boot:run
```

**启用演示运行器：**

```bash
export pipeline.runner.enabled=true
./mvnw -pl agentscope-examples/multiagent-patterns/pipeline spring-boot:run
```

或在 `application.yml` 中设置：`pipeline.runner.enabled: true`。

## 配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.ai.dashscope.api-key` | 环境变量 `AI_DASHSCOPE_API_KEY` | 模型使用的 DashScope API Key |
| `pipeline.runner.enabled` | `false` | 为 `true` 时，启动时运行顺序、并行、循环三个演示 |

## 项目结构

```
agentscope-examples/multiagent-patterns/pipeline/
├── README.md
├── pom.xml
└── src/main/
    ├── java/.../pipeline/
    │   ├── PipelineApplication.java
    │   ├── PipelineModelConfig.java        # Model (DashScopeChatModel) Bean
    │   ├── PipelineService.java            # runSequential, runParallel, runLoop
    │   ├── PipelineCommandRunner.java      # 可选演示（pipeline.runner.enabled）
    │   ├── PipelineRunnerConfig.java       # PipelineService Bean
    │   ├── sequential/
    │   │   └── SequentialPipelineConfig.java
    │   ├── parallel/
    │   │   └── ParallelPipelineConfig.java
    │   └── loop/
    │       └── LoopPipelineConfig.java
    └── resources/
        └── application.yml
```

## 相关文档

- [Routing](./routing.md) - 分类并路由到专家智能体
- [MsgHub](../task/msghub.md) - 多智能体对话的消息广播
- [Handoffs](./handoffs.md) - 状态驱动路由与智能体间交接
- [多智能体辩论](./multiagent-debate.md) - 辩论工作流模式
