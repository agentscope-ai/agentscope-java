# 管道

管道为 AgentScope 中的多智能体工作流提供组合模式。

## 管道类型

AgentScope 提供两种主要管道类型：

- **SequentialPipeline**：智能体按顺序执行，每个接收前一个智能体的输出
- **FanoutPipeline**：多个智能体并行处理相同输入

## SequentialPipeline

按顺序执行智能体：

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

// 创建智能体
ReActAgent agent1 = ReActAgent.builder().name("Agent1").model(model).build();
ReActAgent agent2 = ReActAgent.builder().name("Agent2").model(model).build();
ReActAgent agent3 = ReActAgent.builder().name("Agent3").model(model).build();

// 创建顺序管道
Msg input = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(List.of(TextBlock.builder().text("处理这个").build()))
        .build();

Mono<Msg> response = Pipelines.sequential(List.of(agent1, agent2, agent3), input);

// 执行管道
response.subscribe(msg -> System.out.println(msg.getTextContent()));
```

## FanoutPipeline

并行执行智能体：

```java
// 创建并行管道
Mono<List<Msg>> response = Pipelines.fanout(List.of(agent1, agent2, agent3), input);
```
