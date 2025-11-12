# 工具

工具使智能体能够执行文本生成之外的操作，例如调用 API、执行代码或访问外部系统。

## 工具系统概述

AgentScope Java 提供了一个全面的工具系统，具有以下特性：

- 基于**注解**的 Java 方法工具注册
- 支持**同步**和**异步**工具
- **类型安全**的参数绑定
- **自动** JSON schema 生成
- **流式**工具响应（计划中）
- **工具组**用于动态工具管理

## 创建工具

### 基础工具

使用 `@Tool` 和 `@ToolParam` 注解创建工具：

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class WeatherService {

    @Tool(description = "获取指定地点的当前天气")
    public String getWeather(
            @ToolParam(name = "location", description = "城市名称")
            String location) {
        // 调用天气 API
        return location + " 的天气：晴天，25°C";
    }
}
```

> **重要提示**：`@ToolParam` 注解**需要**显式的 `name` 属性，因为 Java 默认不会在运行时保留参数名称。

### 异步工具

使用 `Mono` 或 `Flux` 进行异步操作：

```java
import reactor.core.publisher.Mono;
import java.time.Duration;

public class AsyncService {

    @Tool(description = "异步搜索网络")
    public Mono<String> searchWeb(
            @ToolParam(name = "query", description = "搜索查询")
            String query) {
        return Mono.delay(Duration.ofSeconds(1))
                .map(ignored -> "搜索结果：" + query);
    }
}
```

### 多个参数

工具可以有多个参数：

```java
public class Calculator {

    @Tool(description = "计算两个数的和")
    public int add(
            @ToolParam(name = "a", description = "第一个数") int a,
            @ToolParam(name = "b", description = "第二个数") int b) {
        return a + b;
    }

    @Tool(description = "计算数的幂")
    public double power(
            @ToolParam(name = "base", description = "底数") double base,
            @ToolParam(name = "exponent", description = "指数") double exponent) {
        return Math.pow(base, exponent);
    }
}
```

## Toolkit

`Toolkit` 类管理工具注册和执行。

### 注册工具

```java
import io.agentscope.core.tool.Toolkit;

Toolkit toolkit = new Toolkit();

// 从对象注册所有 @Tool 方法
toolkit.registerTool(new WeatherService());
toolkit.registerTool(new Calculator());

// 一次注册多个对象
toolkit.registerTool(
        new WeatherService(),
        new Calculator(),
        new DataService()
);
```

### 与智能体一起使用

```java
import io.agentscope.core.ReActAgent;

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)  // 为智能体提供工具包
        .sysPrompt("你是一个有帮助的助手。在需要时使用工具。")
        .build();
```

## 工具模式

AgentScope 自动为工具生成 JSON schema：

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

// 获取所有工具 schema
List<ToolSchema> schemas = toolkit.getToolSchemas();

for (ToolSchema schema : schemas) {
    System.out.println("工具: " + schema.getName());
    System.out.println("描述: " + schema.getDescription());
    System.out.println("参数: " + schema.getParameters());
}
```

## 完整示例

```java
package io.agentscope.tutorial.task;

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

public class ToolExample {

    /** 天气服务工具 */
    public static class WeatherService {
        @Tool(description = "获取城市的当前天气")
        public String getWeather(
                @ToolParam(name = "city", description = "城市名称")
                String city) {
            // 模拟 API 调用
            return String.format("%s 的天气：晴天，25°C", city);
        }

        @Tool(description = "获取未来 N 天的天气预报")
        public String getForecast(
                @ToolParam(name = "city", description = "城市名称")
                String city,
                @ToolParam(name = "days", description = "天数")
                int days) {
            return String.format("%s 的 %d 天预报：大部分晴天", city, days);
        }
    }

    /** 计算器工具 */
    public static class Calculator {
        @Tool(description = "两数相加")
        public double add(
                @ToolParam(name = "a", description = "第一个数") double a,
                @ToolParam(name = "b", description = "第二个数") double b) {
            return a + b;
        }

        @Tool(description = "两数相乘")
        public double multiply(
                @ToolParam(name = "a", description = "第一个数") double a,
                @ToolParam(name = "b", description = "第二个数") double b) {
            return a * b;
        }
    }

    public static void main(String[] args) {
        // 创建模型
        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .build();

        // 创建工具包并注册工具
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherService());
        toolkit.registerTool(new Calculator());

        // 使用工具创建智能体
        ReActAgent agent = ReActAgent.builder()
                .name("Assistant")
                .sysPrompt("你是一个有帮助的助手。使用可用的工具回答问题。")
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .maxIters(5)
                .build();

        // 测试工具使用
        Msg question = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(
                        "北京的天气怎么样？另外，15 + 27 等于多少？"
                )))
                .build();

        Msg response = agent.call(question).block();
        System.out.println("问题: " + question.getTextContent());
        System.out.println("答案: " + response.getTextContent());

        // 检查工具 schema
        System.out.println("\n已注册的工具:");
        for (var schema : toolkit.getToolSchemas()) {
            System.out.println("- " + schema.getName() + ": " + schema.getDescription());
        }
    }
}
```
