# 管道

管道为 AgentScope 中的多智能体工作流提供组合模式。

## 管道类型

AgentScope 提供两种主要管道类型：

- **SequentialPipeline**：智能体按顺序执行，每个接收前一个智能体的输出
- **FanoutPipeline**：多个智能体并行处理相同输入

## SequentialPipeline

按顺序执行智能体：

```java
import io.agentscope.core.pipeline.Pipeline;
import io.agentscope.core.pipeline.Pipelines;

// 创建智能体
ReActAgent agent1 = ReActAgent.builder().name("Agent1").model(model).build();
ReActAgent agent2 = ReActAgent.builder().name("Agent2").model(model).build();
ReActAgent agent3 = ReActAgent.builder().name("Agent3").model(model).build();

// 创建顺序管道
Pipeline pipeline = Pipelines.sequential(agent1, agent2, agent3);

// 执行管道
Msg input = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(List.of(TextBlock.builder().text("处理这个").build()))
        .build();

Msg result = pipeline.call(input).block();
```

## FanoutPipeline

并行执行智能体：

```java
// 创建扇出管道
Pipeline pipeline = Pipelines.fanout(agent1, agent2, agent3);

// 所有智能体并行处理相同输入
Msg result = pipeline.call(input).block();
```
