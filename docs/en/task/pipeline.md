# Pipeline

Pipelines provide composition patterns for multi-agent workflows in AgentScope.

## Pipeline Types

AgentScope provides two main pipeline types:

- **SequentialPipeline**: Agents execute in order, each receiving the previous agent's output
- **FanoutPipeline**: Multiple agents process the same input in parallel

## SequentialPipeline

Execute agents sequentially:

```java
import io.agentscope.core.pipeline.Pipeline;
import io.agentscope.core.pipeline.Pipelines;

// Create agents
ReActAgent agent1 = ReActAgent.builder().name("Agent1").model(model).build();
ReActAgent agent2 = ReActAgent.builder().name("Agent2").model(model).build();
ReActAgent agent3 = ReActAgent.builder().name("Agent3").model(model).build();

// Create sequential pipeline
Pipeline pipeline = Pipelines.sequential(agent1, agent2, agent3);

// Execute pipeline
Msg input = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(List.of(TextBlock.builder().text("Process this").build()))
        .build();

Msg result = pipeline.call(input).block();
```

## FanoutPipeline

Execute agents in parallel:

```java
// Create fanout pipeline
Pipeline pipeline = Pipelines.fanout(agent1, agent2, agent3);

// All agents process the same input in parallel
Msg result = pipeline.call(input).block();
```
