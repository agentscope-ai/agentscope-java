# 工具执行引擎

本文深入介绍 AgentScope 工具系统的内部实现，包括工具注册、Schema 生成、参数注入和执行调度。

## 架构概览

工具系统由以下核心组件构成：

```
                    ┌─────────────────────────────────────────┐
                    │               Toolkit                    │
                    │  ┌─────────────────────────────────────┐│
                    │  │         Tool Registry               ││
                    │  │  ┌───────────┐  ┌───────────┐      ││
                    │  │  │ AgentTool │  │ AgentTool │ ...  ││
                    │  │  └───────────┘  └───────────┘      ││
                    │  └─────────────────────────────────────┘│
                    │  ┌─────────────────────────────────────┐│
                    │  │         Tool Groups                 ││
                    │  └─────────────────────────────────────┘│
                    │  ┌─────────────────────────────────────┐│
                    │  │      Preset Parameters              ││
                    │  └─────────────────────────────────────┘│
                    │  ┌─────────────────────────────────────┐│
                    │  │    ToolExecutionContext             ││
                    │  └─────────────────────────────────────┘│
                    └─────────────────────────────────────────┘
                                        │
                                        ▼
                    ┌─────────────────────────────────────────┐
                    │            Tool Executor                │
                    │  ┌─────────────┐  ┌─────────────────┐  │
                    │  │ Parameter   │  │ Result          │  │
                    │  │ Injector    │  │ Processor       │  │
                    │  └─────────────┘  └─────────────────┘  │
                    └─────────────────────────────────────────┘
```

## 工具注册

### 注解解析

当注册带 `@Tool` 注解的对象时，框架通过反射解析方法信息：

```java
// 注册过程
toolkit.registerTool(new WeatherService());

// 框架内部处理
for (Method method : clazz.getDeclaredMethods()) {
    Tool annotation = method.getAnnotation(Tool.class);
    if (annotation != null) {
        AgentTool tool = buildToolFromMethod(instance, method, annotation);
        registry.put(tool.getName(), tool);
    }
}
```

### 方法到 AgentTool 的转换

```java
@Tool(name = "get_weather", description = "获取天气")
public String getWeather(
    @ToolParam(name = "city", description = "城市") String city,
    @ToolParam(name = "date", description = "日期", required = false) String date
) { ... }

// 转换为 AgentTool
AgentTool {
    name: "get_weather",
    description: "获取天气",
    parameters: {
        "type": "object",
        "properties": {
            "city": {"type": "string", "description": "城市"},
            "date": {"type": "string", "description": "日期"}
        },
        "required": ["city"]
    }
}
```

## Schema 生成

### JSON Schema 结构

工具参数按 JSON Schema 格式生成，供 LLM 理解：

```json
{
  "type": "object",
  "properties": {
    "city": {
      "type": "string",
      "description": "城市名称"
    },
    "days": {
      "type": "integer",
      "description": "预报天数"
    }
  },
  "required": ["city"]
}
```

### Java 类型到 JSON Schema 映射

| Java 类型 | JSON Schema 类型 |
|-----------|------------------|
| `String` | `"string"` |
| `int`, `Integer` | `"integer"` |
| `long`, `Long` | `"integer"` |
| `double`, `Double` | `"number"` |
| `float`, `Float` | `"number"` |
| `boolean`, `Boolean` | `"boolean"` |
| `List<T>` | `"array"` |
| `Map<String, T>` | `"object"` |
| POJO | `"object"` (嵌套属性) |

### 复杂类型处理

```java
// POJO 参数
public class SearchParams {
    private String query;
    private int limit;
    private List<String> filters;
}

@Tool(description = "搜索")
public String search(@ToolParam(name = "params") SearchParams params) { ... }

// 生成的 Schema
{
  "type": "object",
  "properties": {
    "params": {
      "type": "object",
      "properties": {
        "query": {"type": "string"},
        "limit": {"type": "integer"},
        "filters": {"type": "array", "items": {"type": "string"}}
      }
    }
  }
}
```

## 参数注入机制

工具执行时，参数来自多个来源，按优先级注入。

### 注入优先级

```
┌─────────────────────────────────────────────────────────────┐
│                     参数来源优先级                           │
│                                                             │
│  优先级 1: LLM 提供的参数                                    │
│     ↓                                                       │
│  优先级 2: 预设参数 (Preset Parameters)                      │
│     ↓                                                       │
│  优先级 3: 执行上下文 (ToolExecutionContext)                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 预设参数

预设参数在注册时配置，对 LLM 不可见但执行时自动注入：

```java
// 定义工具
@Tool(description = "发送邮件")
public String sendEmail(
    @ToolParam(name = "to") String to,
    @ToolParam(name = "subject") String subject,
    @ToolParam(name = "apiKey") String apiKey  // 隐藏参数
) { ... }

// 注册时设置预设参数
toolkit.registration()
    .tool(new EmailService())
    .presetParameters(Map.of(
        "sendEmail", Map.of("apiKey", System.getenv("EMAIL_API_KEY"))
    ))
    .apply();
```

**效果**：
- LLM 看到的 Schema 只包含 `to` 和 `subject`
- 执行时 `apiKey` 自动从预设参数注入

### 执行上下文

执行上下文通过类型匹配自动注入对象：

```java
// 定义上下文类
public class UserContext {
    private String userId;
    private String role;
    // getters...
}

// 工具方法接收上下文
@Tool(description = "获取用户数据")
public String getUserData(
    @ToolParam(name = "dataType") String dataType,
    UserContext ctx  // 无需 @ToolParam，按类型注入
) {
    return "User " + ctx.getUserId() + " data: " + dataType;
}

// 配置上下文
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user123", "admin"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .toolExecutionContext(context)
    .build();
```

### 上下文优先级链

上下文可以在多个层级配置，形成优先级链：

```
Call-level Context (最高优先级)
        ↓
Agent-level Context
        ↓
Toolkit-level Context (最低优先级)
```

```java
// Toolkit 级别
ToolExecutionContext toolkitCtx = ToolExecutionContext.builder()
    .register(new DatabaseConfig("jdbc:mysql://default"))
    .build();

Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .defaultContext(toolkitCtx)
    .build());

// Agent 级别（覆盖 Toolkit 级别）
ToolExecutionContext agentCtx = ToolExecutionContext.builder()
    .register(new UserContext("user123"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .toolExecutionContext(agentCtx)
    .build();
```

## 特殊参数注入

某些参数类型由框架自动注入，无需 `@ToolParam`。

### ToolEmitter

用于流式输出工具进度：

```java
@Tool(description = "批量处理")
public ToolResultBlock batchProcess(
    @ToolParam(name = "items") List<String> items,
    ToolEmitter emitter  // 自动注入
) {
    for (int i = 0; i < items.size(); i++) {
        processItem(items.get(i));
        emitter.emit(ToolResultBlock.text("进度: " + (i + 1) + "/" + items.size()));
    }
    return ToolResultBlock.text("完成");
}
```

### Agent 引用

工具可以获取当前执行的 Agent 实例：

```java
@Tool(description = "获取智能体名称")
public String getAgentName(Agent agent) {  // 自动注入
    return agent.getName();
}
```

## 执行调度

### 同步 vs 异步执行

框架支持同步和异步工具：

```java
// 同步工具
@Tool(description = "同步计算")
public int add(int a, int b) {
    return a + b;
}

// 异步工具
@Tool(description = "异步搜索")
public Mono<String> search(String query) {
    return webClient.get()
        .uri("/search?q=" + query)
        .retrieve()
        .bodyToMono(String.class);
}
```

### 并行执行

当 LLM 返回多个工具调用时，可以并行执行：

```java
ToolkitConfig config = ToolkitConfig.builder()
    .parallel(true)
    .build();
```

**并行执行流程**：

```
LLM 返回: [ToolUseBlock A, ToolUseBlock B, ToolUseBlock C]
                    │
                    ▼
            ┌───────┴───────┐
            │   并行执行器   │
            └───────┬───────┘
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
    执行 A       执行 B       执行 C
        │           │           │
        └───────────┼───────────┘
                    │
                    ▼
            [结果 A, 结果 B, 结果 C]
```

### 超时和重试

```java
ToolkitConfig config = ToolkitConfig.builder()
    .executionConfig(ExecutionConfig.builder()
        .timeout(Duration.ofSeconds(30))
        .maxAttempts(3)
        .initialBackoff(Duration.ofSeconds(1))
        .backoffMultiplier(2.0)
        .build())
    .build();
```

## 工具组管理

工具组允许动态激活/停用工具集合。

### 内部结构

```java
class Toolkit {
    // 所有已注册的工具
    private Map<String, AgentTool> allTools;

    // 工具组定义
    private Map<String, ToolGroup> toolGroups;

    // 当前激活的组
    private Set<String> activeGroups;

    // 获取当前可用工具（只返回激活组中的工具）
    public List<AgentTool> getActiveTools() {
        return allTools.values().stream()
            .filter(tool -> isToolActive(tool))
            .toList();
    }
}
```

### 动态切换

```java
// 创建工具组
toolkit.createToolGroup("basic", "基础工具", true);
toolkit.createToolGroup("admin", "管理员工具", false);

// 注册到组
toolkit.registration()
    .tool(new BasicTools())
    .group("basic")
    .apply();

// 动态切换
toolkit.updateToolGroups(List.of("admin"), true);   // 激活
toolkit.updateToolGroups(List.of("basic"), false);  // 停用
```

## 结果处理

### 返回值转换

工具返回值自动转换为 `ToolResultBlock`：

| 返回类型 | 转换结果 |
|----------|----------|
| `String` | `ToolResultBlock.text(value)` |
| `ToolResultBlock` | 直接使用 |
| `Mono<String>` | 异步等待后转换 |
| `Mono<ToolResultBlock>` | 异步等待后使用 |
| 其他对象 | JSON 序列化后包装为 TextBlock |

### 错误处理

工具执行异常自动转换为错误结果：

```java
@Tool(description = "可能失败的操作")
public String riskyOperation(String input) {
    if (invalid(input)) {
        throw new IllegalArgumentException("无效输入");
    }
    return "成功";
}

// 异常自动转换为:
ToolResultBlock.error(toolUseId, "无效输入");
```

## 相关文档

- [ReAct 循环原理](./react-loop.md)
- [工具系统使用](../task/tool.md)
