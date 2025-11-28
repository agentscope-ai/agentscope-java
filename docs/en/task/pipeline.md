# Pipeline

Pipelines provide composition patterns for multi-agent workflows in AgentScope.

## Pipeline Types

AgentScope provides two main pipeline types:

- **SequentialPipeline**: Agents execute in order, each receiving the previous agent's output
- **FanoutPipeline**: Multiple agents process the same input in parallel

## SequentialPipeline

Execute agents sequentially:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.pipeline.Pipelines;
import reactor.core.publisher.Mono;
import java.util.List;

DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("API_KEY"))
        .modelName("qwen-plus")
        .build();

// Create agents
ReActAgent agent1 = ReActAgent.builder().name("Agent1").model(model).build();
ReActAgent agent2 = ReActAgent.builder().name("Agent2").model(model).build();
ReActAgent agent3 = ReActAgent.builder().name("Agent3").model(model).build();

// Create sequential pipeline
Msg input = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(List.of(TextBlock.builder().text("Process this").build()))
        .build();

Mono<Msg> response = Pipelines.sequential(List.of(agent1, agent2, agent3), input);

// Execute pipeline
response.subscribe(msg -> System.out.println(msg.getTextContent()));
```

## FanoutPipeline

Execute agents in parallel:

```java
// Create fanout pipeline
Mono<List<Msg>> response = Pipelines.fanout(List.of(agent1, agent2, agent3), input);
```
