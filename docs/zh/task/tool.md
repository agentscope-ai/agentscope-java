# 工具

工具使智能体能够执行文本生成之外的操作，例如调用 API、执行代码或访问外部系统。

## 工具系统概述

AgentScope Java 提供了一个全面的工具系统，具有以下特性：

- 基于**注解**的 Java 方法工具注册
- 支持**同步**和**异步**工具
- **类型安全**的参数绑定
- **自动** JSON schema 生成
- **流式**工具响应
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

## 高级注册选项

对于需要更多配置的场景，使用 Builder API 提供了更清晰的语法：

### 使用 Builder 注册工具

```java
// 基础注册
toolkit.registration()
    .tool(new WeatherService())
    .apply();

// 指定工具组
toolkit.registration()
    .tool(new WeatherService())
    .group("weatherTools")
    .apply();

// 注册 AgentTool 实例
toolkit.registration()
    .agentTool(customAgentTool)
    .group("customTools")
    .apply();

// 组合多个选项
toolkit.registration()
    .tool(new APIService())
    .group("apiTools")
    .presetParameters(Map.of("apiKey", "secret"))
    .extendedModel(customModel)
    .apply();

// 注册 MCP 客户端
toolkit.registration()
    .mcpClient(mcpClientWrapper)
    .enableTools(List.of("tool1", "tool2"))
    .group("mcpTools")
    .apply();
```

### Builder API 优势

- **清晰度**：参数意图明确，无需记住参数顺序
- **可选性**：仅设置需要的参数
- **类型安全**：编译期检查所有配置
- **可扩展**：未来添加新选项无需修改现有代码

## 预设参数

预设参数允许你在工具注册时设置默认参数值，这些参数会在执行时自动注入，但不会暴露在 JSON schema 中。这对于传递上下文信息（如 API 密钥、用户 ID、会话信息）非常有用。

### 注册带预设参数的工具

```java
import java.util.Map;

public class APIService {
    @Tool(description = "调用外部 API")
    public String callAPI(
            @ToolParam(name = "query", description = "查询内容") String query,
            @ToolParam(name = "apiKey", description = "API 密钥") String apiKey,
            @ToolParam(name = "userId", description = "用户 ID") String userId) {
        // 使用 apiKey 和 userId 调用 API
        return String.format("用户 %s 查询 '%s' 的结果", userId, query);
    }
}

// 注册工具时提供预设参数
Toolkit toolkit = new Toolkit();
Map<String, Map<String, Object>> presetParams = Map.of(
    "callAPI", Map.of(
        "apiKey", "sk-your-api-key",
        "userId", "user-123"
    )
);
toolkit.registration()
    .tool(new APIService())
    .presetParameters(presetParams)
    .apply();
```

在上述示例中：
- `apiKey` 和 `userId` 会被自动注入到每次工具调用中
- 这些参数**不会**出现在工具的 JSON schema 中
- 智能体只需要提供 `query` 参数

### 参数优先级

智能体提供的参数可以覆盖预设参数：

```java
// 预设参数：apiKey="default-key", userId="default-user"
Map<String, Map<String, Object>> presetParams = Map.of(
    "callAPI", Map.of("apiKey", "default-key", "userId", "default-user")
);
toolkit.registration()
    .tool(service)
    .presetParameters(presetParams)
    .apply();

// 智能体调用时提供 userId，将覆盖预设值
// 实际执行：query="test", apiKey="default-key", userId="agent-user"
```

### 运行时更新预设参数

你可以在运行时动态更新工具的预设参数：

```java
// 初始注册
Map<String, Map<String, Object>> initialParams = Map.of(
    "sessionTool", Map.of("sessionId", "session-001")
);
toolkit.registration()
    .tool(new SessionTool())
    .presetParameters(initialParams)
    .apply();

// 后续更新会话 ID
Map<String, Object> updatedParams = Map.of("sessionId", "session-002");
toolkit.updateToolPresetParameters("sessionTool", updatedParams);
```

### MCP 工具的预设参数

MCP（Model Context Protocol）工具也支持预设参数，使用 Builder API 进行配置：

```java
// 为不同的 MCP 工具设置不同的预设参数
Map<String, Map<String, Object>> presetMapping = Map.of(
    "tool1", Map.of("apiKey", "key1", "region", "us-west"),
    "tool2", Map.of("apiKey", "key2", "region", "eu-central")
);

toolkit.registration()
    .mcpClient(mcpClientWrapper)
    .enableTools(List.of("tool1", "tool2"))
    .disableTools(List.of("tool3"))
    .group("mcp-group")
    .presetParameters(presetMapping)
    .apply();
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

## 工具执行上下文

工具执行上下文允许你向工具方法传递自定义上下文对象,而不会暴露在 LLM 可见的工具 schema 中。

### 基础用法

#### 1. 定义你的上下文类

```java
public class UserContext {
    private String userId;
    private String sessionId;

    public UserContext(String userId, String sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
    }

    // Getters 和 setters...
}
```

#### 2. 在 Agent 级别注册上下文

```java
import io.agentscope.core.tool.ToolExecutionContext;

// 创建上下文
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user123", "session456"))
    .build();

// 在 agent 中配置
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .toolExecutionContext(context)
    .build();
```

#### 3. 在工具方法中使用上下文

```java
public class DatabaseService {

    @Tool(description = "查询数据库")
    public ToolResultBlock queryDatabase(
            @ToolParam(name = "query") String query,
            UserContext context  // 自动注入,不需要 @ToolParam
    ) {
        String userId = context.getUserId();
        String result = executeQuery(query, userId);
        return ToolResultBlock.text(result);
    }
}
```

### 注意事项

- 上下文对象通过类型自动注入
- 不会出现在工具的 JSON schema 中
- 可以向同一个工具传递多种上下文类型

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
    }
}
```
