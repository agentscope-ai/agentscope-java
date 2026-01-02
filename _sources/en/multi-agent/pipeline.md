# Pipeline

Pipelines provide composition patterns for multi-agent workflows in AgentScope. They serve as syntax sugar for chaining agents together, simplifying complex orchestration logic.

## Overview

AgentScope provides two main pipeline types:

- **SequentialPipeline**: Agents execute in order, each receiving the previous agent's output
- **FanoutPipeline**: Multiple agents process the same input (in parallel or sequentially)

Additionally, the `Pipelines` utility class provides static factory methods for quick pipeline creation.

## SequentialPipeline

SequentialPipeline executes agents one by one, where the output of the previous agent becomes the input of the next agent.

```
Input → Agent1 → Agent2 → Agent3 → Output
```

### Basic Usage

Use the `Pipelines.sequential()` static method for quick execution:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.pipeline.Pipelines;

import java.util.List;

// Create model
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .build();

// Create agents for different stages
ReActAgent researcher = ReActAgent.builder()
        .name("Researcher")
        .sysPrompt("You are a researcher. Analyze the topic and provide key findings.")
        .model(model)
        .build();

ReActAgent writer = ReActAgent.builder()
        .name("Writer")
        .sysPrompt("You are a writer. Based on the research findings, write a concise summary.")
        .model(model)
        .build();

ReActAgent editor = ReActAgent.builder()
        .name("Editor")
        .sysPrompt("You are an editor. Polish and finalize the summary.")
        .model(model)
        .build();

// Create input message
Msg input = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(TextBlock.builder().text("Artificial Intelligence in Healthcare").build())
        .build();

// Execute sequential pipeline
// Researcher → Writer → Editor
Msg result = Pipelines.sequential(List.of(researcher, writer, editor), input).block();

System.out.println("Final result: " + result.getTextContent());
```

### Using Builder Pattern

For reusable pipelines, use `SequentialPipeline.builder()`:

```java
import io.agentscope.core.pipeline.SequentialPipeline;

// Create a reusable pipeline
SequentialPipeline pipeline = SequentialPipeline.builder()
        .addAgent(researcher)
        .addAgent(writer)
        .addAgent(editor)
        .build();

// Execute the pipeline
Msg result1 = pipeline.execute(input).block();

// Reuse with different input
Msg anotherInput = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(TextBlock.builder().text("Climate Change Solutions").build())
        .build();

Msg result2 = pipeline.execute(anotherInput).block();
```

### Structured Output Support

The last agent in the pipeline can produce structured output:

```java
// Define output structure
public class ArticleSummary {
    public String title;
    public String summary;
    public List<String> keyPoints;
}

// Execute with structured output (only applies to the last agent)
Msg result = pipeline.execute(input, ArticleSummary.class).block();

// Extract structured data
ArticleSummary article = result.getStructuredData(ArticleSummary.class);
System.out.println("Title: " + article.title);
System.out.println("Summary: " + article.summary);
```

## FanoutPipeline

FanoutPipeline distributes the same input to multiple agents and collects all their responses. This is useful when you want to gather different perspectives or expertise on the same topic.

```
         ┌→ Agent1 → Output1
Input →──┼→ Agent2 → Output2
         └→ Agent3 → Output3
```

### Basic Usage

Use the `Pipelines.fanout()` static method for concurrent execution:

```java
import io.agentscope.core.pipeline.Pipelines;

// Create agents with different perspectives
ReActAgent optimist = ReActAgent.builder()
        .name("Optimist")
        .sysPrompt("You are an optimist. Analyze the positive aspects of the topic.")
        .model(model)
        .build();

ReActAgent pessimist = ReActAgent.builder()
        .name("Pessimist")
        .sysPrompt("You are a pessimist. Analyze the potential risks and challenges.")
        .model(model)
        .build();

ReActAgent realist = ReActAgent.builder()
        .name("Realist")
        .sysPrompt("You are a realist. Provide a balanced analysis.")
        .model(model)
        .build();

// Execute fanout pipeline (concurrent by default)
List<Msg> results = Pipelines.fanout(
        List.of(optimist, pessimist, realist),
        input
).block();

// Process all results
for (Msg result : results) {
    System.out.println(result.getName() + ": " + result.getTextContent());
}
```

### Concurrent vs Sequential Execution

FanoutPipeline supports two execution modes:

| Mode | Method | Behavior | Use Case |
|------|--------|----------|----------|
| **Concurrent** | `fanout()` | All agents run in parallel using `boundedElastic()` scheduler | Better performance for I/O-bound operations |
| **Sequential** | `fanoutSequential()` | Agents run one by one | Predictable ordering, resource control |

```java
// Concurrent execution (default) - better for API calls
List<Msg> concurrent = Pipelines.fanout(agents, input).block();

// Sequential execution - predictable order
List<Msg> sequential = Pipelines.fanoutSequential(agents, input).block();
```

### Using Builder Pattern

```java
import io.agentscope.core.pipeline.FanoutPipeline;

// Create concurrent fanout pipeline
FanoutPipeline concurrentPipeline = FanoutPipeline.builder()
        .addAgent(optimist)
        .addAgent(pessimist)
        .addAgent(realist)
        .concurrent()  // Default mode
        .build();

// Create sequential fanout pipeline
FanoutPipeline sequentialPipeline = FanoutPipeline.builder()
        .addAgent(optimist)
        .addAgent(pessimist)
        .addAgent(realist)
        .sequential()
        .build();

// Execute
List<Msg> results = concurrentPipeline.execute(input).block();
```

## Pipelines Utility Class

The `Pipelines` class provides static factory methods for quick pipeline operations:

### Method Reference

| Method | Return Type | Description |
|--------|-------------|-------------|
| `sequential(agents, input)` | `Mono<Msg>` | Execute agents sequentially with input |
| `sequential(agents)` | `Mono<Msg>` | Execute agents sequentially without input |
| `sequential(agents, input, outputClass)` | `Mono<Msg>` | Sequential with structured output |
| `fanout(agents, input)` | `Mono<List<Msg>>` | Execute agents concurrently |
| `fanout(agents)` | `Mono<List<Msg>>` | Execute agents concurrently without input |
| `fanoutSequential(agents, input)` | `Mono<List<Msg>>` | Execute agents sequentially (same input) |
| `createSequential(agents)` | `SequentialPipeline` | Create reusable sequential pipeline |
| `createFanout(agents)` | `FanoutPipeline` | Create reusable concurrent fanout pipeline |
| `createFanoutSequential(agents)` | `FanoutPipeline` | Create reusable sequential fanout pipeline |

### Pipeline Composition

You can compose multiple pipelines:

```java
// Create two sequential pipelines
SequentialPipeline research = Pipelines.createSequential(List.of(researcher, analyst));
SequentialPipeline writing = Pipelines.createSequential(List.of(writer, editor));

// Compose them into a larger pipeline
Pipeline<Msg> combined = Pipelines.compose(research, writing);

// Execute the combined pipeline
Msg result = combined.execute(input).block();
```

## Combining Pipeline with MsgHub

For complex workflows, you can combine Pipeline with MsgHub:

```java
import io.agentscope.core.pipeline.MsgHub;

// Stage 1: Parallel analysis using FanoutPipeline
List<Msg> analyses = Pipelines.fanout(List.of(optimist, pessimist, realist), input).block();

// Stage 2: Group discussion using MsgHub
try (MsgHub hub = MsgHub.builder()
        .participants(optimist, pessimist, realist)
        .build()) {

    hub.enter().block();

    // Broadcast all analyses
    hub.broadcast(analyses).block();

    // Each agent responds to others' analyses
    optimist.call().block();
    pessimist.call().block();
    realist.call().block();
}

// Stage 3: Final synthesis using SequentialPipeline
ReActAgent synthesizer = ReActAgent.builder()
        .name("Synthesizer")
        .sysPrompt("Synthesize all perspectives into a final conclusion.")
        .model(model)
        .build();

Msg conclusion = synthesizer.call(input).block();
```

## Related Documentation

- [MsgHub](./msghub.md) - Message broadcasting for multi-agent conversations
- [Multi-Agent Debate](./multiagent-debate.md) - Debate workflow pattern
