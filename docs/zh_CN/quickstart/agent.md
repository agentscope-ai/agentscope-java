# 智能体

AgentScope 提供了一个开箱即用的 ReAct 智能体实现，支持钩子、工具、内存、结构化输出和实时中断。

### 基础示例

```java
package io.agentscope.tutorial.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import java.util.List;

public class AgentExample {
    public static void main(String[] args) {
        // 构建智能体
        ReActAgent agent = ReActAgent.builder()
                .name("Jarvis")
                .sysPrompt("你是一个名叫 Jarvis 的有帮助的助手。")
                .model(DashScopeChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        .modelName("qwen-plus")
                        .build())
                .toolkit(new Toolkit())
                .memory(new InMemoryMemory())
                .maxIters(10)
                .build();

        // 创建输入消息
        Msg inputMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text("你好！你能做什么？").build()))
                .build();

        // 调用智能体（阻塞）
        Msg response = agent.call(inputMsg).block();
        System.out.println("智能体: " + response.getTextContent());
    }
}
```

## 构建器参数

`ReActAgent.Builder` 提供以下参数：

| 参数                  | 类型                    | 必需 | 描述                                              |
|-----------------------|-------------------------|------|---------------------------------------------------|
| name                  | String                  | 是   | 智能体的名称                                       |
| sysPrompt             | String                  | 是   | 智能体的系统提示词                                 |
| model                 | Model                   | 是   | 用于推理的 LLM 模型                                |
| toolkit               | Toolkit                 | 否   | 智能体可用的工具（默认：空）                       |
| memory                | Memory                  | 否   | 短期内存（默认：InMemoryMemory）                   |
| maxIters              | int                     | 否   | 最大推理-行动迭代次数（默认：10）                  |
| hooks                 | List<Hook>              | 否   | 用于监控执行的钩子（默认：空）                     |
| modelExecutionConfig  | ExecutionConfig         | 否   | 模型调用的超时/重试配置                            |
| toolExecutionConfig   | ExecutionConfig         | 否   | 工具调用的超时/重试配置                            |

## 智能体方法

### call()

处理输入消息并生成响应：

```java
// 使用单个消息调用
Mono<Msg> response = agent.call(inputMsg);

// 使用多个消息调用
Mono<Msg> response = agent.call(List.of(msg1, msg2, msg3));

// 不使用新输入调用（从当前状态继续）
Mono<Msg> response = agent.call();
```

### stream()

流式响应，实时更新：

```java
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;

Flux<Event> eventStream = agent.stream(inputMsg);

eventStream.subscribe(event -> {
    if (event.getEventType() == EventType.TEXT_CHUNK) {
        System.out.print(event.getChunk().getText());
    } else if (event.getEventType() == EventType.TEXT_COMPLETE) {
        System.out.println("\n[完成]");
    }
});
```

### interrupt()

在执行期间中断智能体：

```java
// 在另一个线程中
agent.interrupt();

// 或使用消息中断
Msg interruptMsg = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(List.of(TextBlock.builder().text("请停止。").build()))
        .build();
agent.interrupt(interruptMsg);
```

## 添加工具

工具使智能体能够执行文本生成之外的操作：

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class WeatherService {

    @Tool(description = "获取指定地点的当前天气")
    public String getWeather(
            @ToolParam(name = "location", description = "城市名称")
            String location) {
        // 在此处调用实际的天气 API
        return location + " 的天气是晴天，25°C";
    }
}

// 注册工具
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

// 使用工具构建智能体
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .sysPrompt("你是一个有帮助的助手。使用可用的工具回答问题。")
        .model(DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .build())
        .toolkit(toolkit)
        .build();
```

> **重要提示**：`@ToolParam` 注解需要显式的 `name` 属性，因为 Java 默认不会在运行时保留参数名称。

## 结构化输出

从智能体请求结构化输出：

```java
public class TaskPlan {
    public String goal;
    public List<String> steps;
    public int priority;
}

// 使用结构化输出调用
Mono<Msg> response = agent.call(inputMsg, TaskPlan.class);

// 提取结构化数据
Msg result = response.block();
if (result.hasStructuredData()) {
    TaskPlan plan = result.getStructuredData(TaskPlan.class);
    System.out.println("目标: " + plan.goal);
    System.out.println("步骤: " + plan.steps);
}
```

## 内存管理

AgentScope 通过 `Memory` 接口自动管理对话历史：

```java
import io.agentscope.core.memory.InMemoryMemory;

Memory memory = new InMemoryMemory();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .build())
        .memory(memory)
        .build();

// 智能体自动在内存中存储消息
agent.call(msg1).block();
agent.call(msg2).block();

// 访问对话历史
List<Msg> history = memory.getAllMessages();
System.out.println("总消息数: " + history.size());
```

## 响应式编程

AgentScope Java 使用 Project Reactor 进行异步操作：

```java
// 非阻塞执行
Mono<Msg> responseMono = agent.call(inputMsg);

// 链式操作
responseMono
    .map(msg -> msg.getTextContent())
    .doOnNext(text -> System.out.println("响应: " + text))
    .subscribe();

// 多个并行调用
List<Mono<Msg>> calls = List.of(
    agent.call(msg1),
    agent.call(msg2),
    agent.call(msg3)
);

Flux.merge(calls)
    .collectList()
    .subscribe(responses -> {
        System.out.println("收到所有响应: " + responses.size());
    });
```

## 完整示例（带工具）

```java
package io.agentscope.tutorial.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;

public class CompleteAgentExample {

    // 工具类
    public static class Calculator {
        @Tool(description = "两数相加")
        public int add(
                @ToolParam(name = "a", description = "第一个数") int a,
                @ToolParam(name = "b", description = "第二个数") int b) {
            return a + b;
        }

        @Tool(description = "两数相乘")
        public int multiply(
                @ToolParam(name = "a", description = "第一个数") int a,
                @ToolParam(name = "b", description = "第二个数") int b) {
            return a * b;
        }
    }

    public static void main(String[] args) {
        // 使用计算器工具创建工具包
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Calculator());

        // 构建智能体
        ReActAgent agent =
                ReActAgent.builder()
                        .name("MathAssistant")
                        .sysPrompt("你是一个数学助手。使用计算器工具帮助用户。")
                        .model(DashScopeChatModel.builder()
                                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                .modelName("qwen-plus")
                                .build())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .maxIters(5)
                        .build();

        // 测试智能体
        Msg question =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("(15 + 7) * 3 等于多少？").build()))
                        .build();

        Msg response = agent.call(question).block();
        System.out.println("问题: " + question.getTextContent());
        System.out.println("答案: " + response.getTextContent());
    }
}
```

## 下一步

- [工具](../task/tool.md) - 详细了解工具系统
- [钩子](../task/hook.md) - 使用钩子自定义智能体行为
- [模型](../task/model.md) - 配置不同的 LLM 模型
- [管道](../task/pipeline.md) - 组合多个智能体
