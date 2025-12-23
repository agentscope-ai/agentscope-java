# Pipeline（管道）

Pipeline 为 AgentScope 中的多智能体工作流提供组合模式，是用于串联智能体的语法糖，可简化复杂的编排逻辑。

## 概述

AgentScope 提供两种主要的管道类型：

- **SequentialPipeline**：智能体按顺序执行，每个智能体接收上一个智能体的输出
- **FanoutPipeline**：多个智能体处理相同的输入（并行或顺序执行）

此外，`Pipelines` 工具类提供静态工厂方法，用于快速创建管道。

## SequentialPipeline（顺序管道）

SequentialPipeline 按顺序执行智能体，前一个智能体的输出成为下一个智能体的输入。

```
输入 → Agent1 → Agent2 → Agent3 → 输出
```

### 基本用法

使用 `Pipelines.sequential()` 静态方法快速执行：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.pipeline.Pipelines;

import java.util.List;

// 创建模型
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .build();

// 创建不同阶段的智能体
ReActAgent researcher = ReActAgent.builder()
        .name("Researcher")
        .sysPrompt("你是一名研究员。分析主题并提供关键发现。")
        .model(model)
        .build();

ReActAgent writer = ReActAgent.builder()
        .name("Writer")
        .sysPrompt("你是一名作家。根据研究发现撰写简洁的摘要。")
        .model(model)
        .build();

ReActAgent editor = ReActAgent.builder()
        .name("Editor")
        .sysPrompt("你是一名编辑。润色并定稿摘要。")
        .model(model)
        .build();

// 创建输入消息
Msg input = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(TextBlock.builder().text("人工智能在医疗领域的应用").build())
        .build();

// 执行顺序管道
// Researcher → Writer → Editor
Msg result = Pipelines.sequential(List.of(researcher, writer, editor), input).block();

System.out.println("最终结果: " + result.getTextContent());
```

### 使用 Builder 模式

对于可复用的管道，使用 `SequentialPipeline.builder()`：

```java
import io.agentscope.core.pipeline.SequentialPipeline;

// 创建可复用的管道
SequentialPipeline pipeline = SequentialPipeline.builder()
        .addAgent(researcher)
        .addAgent(writer)
        .addAgent(editor)
        .build();

// 执行管道
Msg result1 = pipeline.execute(input).block();

// 使用不同的输入复用管道
Msg anotherInput = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(TextBlock.builder().text("气候变化解决方案").build())
        .build();

Msg result2 = pipeline.execute(anotherInput).block();
```

### 结构化输出支持

管道中的最后一个智能体可以产生结构化输出：

```java
// 定义输出结构
public class ArticleSummary {
    public String title;
    public String summary;
    public List<String> keyPoints;
}

// 使用结构化输出执行（仅应用于最后一个智能体）
Msg result = pipeline.execute(input, ArticleSummary.class).block();

// 提取结构化数据
ArticleSummary article = result.getStructuredData(ArticleSummary.class);
System.out.println("标题: " + article.title);
System.out.println("摘要: " + article.summary);
```

## FanoutPipeline（扇出管道）

FanoutPipeline 将相同的输入分发给多个智能体，并收集所有响应。当您想要获取同一主题的不同视角或专业意见时，这非常有用。

```
         ┌→ Agent1 → Output1
输入 →──┼→ Agent2 → Output2
         └→ Agent3 → Output3
```

### 基本用法

使用 `Pipelines.fanout()` 静态方法进行并发执行：

```java
import io.agentscope.core.pipeline.Pipelines;

// 创建具有不同视角的智能体
ReActAgent optimist = ReActAgent.builder()
        .name("Optimist")
        .sysPrompt("你是一个乐观主义者。分析主题的积极方面。")
        .model(model)
        .build();

ReActAgent pessimist = ReActAgent.builder()
        .name("Pessimist")
        .sysPrompt("你是一个悲观主义者。分析潜在的风险和挑战。")
        .model(model)
        .build();

ReActAgent realist = ReActAgent.builder()
        .name("Realist")
        .sysPrompt("你是一个现实主义者。提供平衡的分析。")
        .model(model)
        .build();

// 执行扇出管道（默认并发）
List<Msg> results = Pipelines.fanout(
        List.of(optimist, pessimist, realist),
        input
).block();

// 处理所有结果
for (Msg result : results) {
    System.out.println(result.getName() + ": " + result.getTextContent());
}
```

### 并发 vs 顺序执行

FanoutPipeline 支持两种执行模式：

| 模式 | 方法 | 行为 | 使用场景 |
|------|------|------|----------|
| **并发** | `fanout()` | 所有智能体使用 `boundedElastic()` 调度器并行运行 | I/O 密集型操作性能更好 |
| **顺序** | `fanoutSequential()` | 智能体逐个运行 | 可预测的顺序，资源控制 |

```java
// 并发执行（默认）- 更适合 API 调用
List<Msg> concurrent = Pipelines.fanout(agents, input).block();

// 顺序执行 - 可预测的顺序
List<Msg> sequential = Pipelines.fanoutSequential(agents, input).block();
```

### 使用 Builder 模式

```java
import io.agentscope.core.pipeline.FanoutPipeline;

// 创建并发扇出管道
FanoutPipeline concurrentPipeline = FanoutPipeline.builder()
        .addAgent(optimist)
        .addAgent(pessimist)
        .addAgent(realist)
        .concurrent()  // 默认模式
        .build();

// 创建顺序扇出管道
FanoutPipeline sequentialPipeline = FanoutPipeline.builder()
        .addAgent(optimist)
        .addAgent(pessimist)
        .addAgent(realist)
        .sequential()
        .build();

// 执行
List<Msg> results = concurrentPipeline.execute(input).block();
```

## Pipelines 工具类

`Pipelines` 类提供静态工厂方法用于快速管道操作：

### 方法参考

| 方法 | 返回类型 | 描述 |
|------|----------|------|
| `sequential(agents, input)` | `Mono<Msg>` | 带输入顺序执行智能体 |
| `sequential(agents)` | `Mono<Msg>` | 无输入顺序执行智能体 |
| `sequential(agents, input, outputClass)` | `Mono<Msg>` | 带结构化输出的顺序执行 |
| `fanout(agents, input)` | `Mono<List<Msg>>` | 并发执行智能体 |
| `fanout(agents)` | `Mono<List<Msg>>` | 无输入并发执行智能体 |
| `fanoutSequential(agents, input)` | `Mono<List<Msg>>` | 顺序执行智能体（相同输入） |
| `createSequential(agents)` | `SequentialPipeline` | 创建可复用的顺序管道 |
| `createFanout(agents)` | `FanoutPipeline` | 创建可复用的并发扇出管道 |
| `createFanoutSequential(agents)` | `FanoutPipeline` | 创建可复用的顺序扇出管道 |

### 管道组合

您可以组合多个管道：

```java
// 创建两个顺序管道
SequentialPipeline research = Pipelines.createSequential(List.of(researcher, analyst));
SequentialPipeline writing = Pipelines.createSequential(List.of(writer, editor));

// 将它们组合成一个更大的管道
Pipeline<Msg> combined = Pipelines.compose(research, writing);

// 执行组合管道
Msg result = combined.execute(input).block();
```

## 结合 Pipeline 与 MsgHub

对于复杂的工作流，可以将 Pipeline 与 MsgHub 结合使用：

```java
import io.agentscope.core.pipeline.MsgHub;

// 阶段 1：使用 FanoutPipeline 进行并行分析
List<Msg> analyses = Pipelines.fanout(List.of(optimist, pessimist, realist), input).block();

// 阶段 2：使用 MsgHub 进行群组讨论
try (MsgHub hub = MsgHub.builder()
        .participants(optimist, pessimist, realist)
        .build()) {

    hub.enter().block();

    // 广播所有分析结果
    hub.broadcast(analyses).block();

    // 每个智能体回应其他人的分析
    optimist.call().block();
    pessimist.call().block();
    realist.call().block();
}

// 阶段 3：使用 SequentialPipeline 进行最终综合
ReActAgent synthesizer = ReActAgent.builder()
        .name("Synthesizer")
        .sysPrompt("综合所有观点得出最终结论。")
        .model(model)
        .build();

Msg conclusion = synthesizer.call(input).block();
```

## 相关文档

- [MsgHub](./msghub.md) - 多智能体对话的消息广播
- [多智能体辩论](./multiagent-debate.md) - 辩论工作流模式
