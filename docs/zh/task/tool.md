# 工具

工具系统让智能体能够执行 API 调用、数据库查询、文件操作等外部操作。

## 核心特性

- **注解驱动**：使用 `@Tool` 和 `@ToolParam` 快速定义工具
- **响应式编程**：原生支持 `Mono`/`Flux` 异步执行
- **自动 Schema**：自动生成 JSON Schema 供 LLM 理解
- **工具组管理**：动态激活/停用工具集合
- **预设参数**：隐藏敏感参数（如 API Key）
- **并行执行**：支持多工具并行调用

## 快速开始

### 定义工具

```java
public class WeatherService {
    @Tool(description = "获取指定城市的天气")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名称") String city) {
        return city + " 的天气：晴天，25°C";
    }
}
```

> **注意**：`@ToolParam` 的 `name` 属性必须指定，因为 Java 默认不保留参数名。

### 注册和使用

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

ReActAgent agent = ReActAgent.builder()
    .name("助手")
    .model(model)
    .toolkit(toolkit)
    .build();
```

## 工具类型

### 同步工具

直接返回结果，适合快速操作：

```java
@Tool(description = "计算两数之和")
public int add(
        @ToolParam(name = "a", description = "第一个数") int a,
        @ToolParam(name = "b", description = "第二个数") int b) {
    return a + b;
}
```

### 异步工具

返回 `Mono<T>` 或 `Flux<T>`，适合 I/O 操作：

```java
@Tool(description = "异步搜索")
public Mono<String> search(
        @ToolParam(name = "query", description = "搜索词") String query) {
    return webClient.get()
        .uri("/search?q=" + query)
        .retrieve()
        .bodyToMono(String.class);
}
```

### 流式工具

使用 `ToolEmitter` 发送中间进度，适合长时间任务：

```java
@Tool(description = "生成数据")
public ToolResultBlock generate(
        @ToolParam(name = "count") int count,
        ToolEmitter emitter) {  // 自动注入，无需 @ToolParam
    for (int i = 0; i < count; i++) {
        emitter.emit(ToolResultBlock.text("进度 " + i));
    }
    return ToolResultBlock.text("完成");
}
```

### 返回类型

| 返回类型 | 说明 |
|---------|------|
| `String`, `int`, `Object` 等 | 同步执行，自动转换为 `ToolResultBlock` |
| `Mono<T>` | 异步执行 |
| `Flux<T>` | 流式执行 |
| `ToolResultBlock` | 直接控制返回格式（文本、图片、错误等） |

## 工具组

按场景管理工具，支持动态激活/停用：

```java
// 创建工具组
toolkit.createToolGroup("basic", "基础工具", true);   // 默认激活
toolkit.createToolGroup("admin", "管理工具", false);  // 默认停用

// 注册到工具组
toolkit.registration()
    .tool(new BasicTools())
    .group("basic")
    .apply();

// 动态切换
toolkit.updateToolGroups(List.of("admin"), true);   // 激活
toolkit.updateToolGroups(List.of("basic"), false);  // 停用
```

**使用场景**：
- 权限控制：根据用户角色激活不同工具
- 场景切换：不同对话阶段使用不同工具集
- 性能优化：减少 LLM 可见的工具数量

## 预设参数

隐藏敏感参数（如 API Key），不暴露给 LLM：

```java
public class EmailService {
    @Tool(description = "发送邮件")
    public String send(
            @ToolParam(name = "to") String to,
            @ToolParam(name = "subject") String subject,
            @ToolParam(name = "apiKey") String apiKey) {  // 预设，LLM 不可见
        return "已发送";
    }
}

toolkit.registration()
    .tool(new EmailService())
    .presetParameters(Map.of(
        "send", Map.of("apiKey", System.getenv("EMAIL_API_KEY"))
    ))
    .apply();
```

**效果**：LLM 只看到 `to` 和 `subject` 参数，`apiKey` 自动注入。

## 工具执行上下文

传递业务对象（如用户信息）给工具，无需暴露给 LLM：

```java
// 1. 定义上下文类
public class UserContext {
    private final String userId;
    public UserContext(String userId) { this.userId = userId; }
    public String getUserId() { return userId; }
}

// 2. 注册到 Agent
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user-123"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("助手")
    .model(model)
    .toolkit(toolkit)
    .toolExecutionContext(context)
    .build();

// 3. 工具中使用（自动注入）
@Tool(description = "查询用户数据")
public String query(
        @ToolParam(name = "sql") String sql,
        UserContext ctx) {  // 自动注入，无需 @ToolParam
    return "用户 " + ctx.getUserId() + " 的数据";
}
```

> 详细配置参见 [智能体](../quickstart/agent.md) 文档。

## 内置工具

### 文件工具

```java
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;

// 基础注册
toolkit.registerTool(new ReadFileTool());
toolkit.registerTool(new WriteFileTool());

// 安全模式（推荐）：限制文件访问范围
toolkit.registerTool(new ReadFileTool("/safe/workspace"));
toolkit.registerTool(new WriteFileTool("/safe/workspace"));
```

| 工具 | 方法 | 说明 |
|------|------|------|
| `ReadFileTool` | `view_text_file` | 按行范围查看文件 |
| `WriteFileTool` | `write_text_file` | 创建/覆盖/替换文件内容 |
| `WriteFileTool` | `insert_text_file` | 在指定行插入内容 |

### Shell 命令工具

| 工具 | 特性 |
|------|------|
| `ShellCommandTool` | 执行 Shell 命令，支持命令白名单和回调批准机制，并支持超时控制 |

**快速使用：**

```java
import io.agentscope.core.tool.coding.ShellCommandTool;

Function<String, Boolean> callback = cmd -> askUserForApproval(cmd);
toolkit.registerTool(new ShellCommandTool(allowedCommands, callback));
```

### 多模态工具

```java
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;
import io.agentscope.core.tool.multimodal.OpenAIMultiModalTool;

toolkit.registerTool(new DashScopeMultiModalTool(System.getenv("DASHSCOPE_API_KEY")));
toolkit.registerTool(new OpenAIMultiModalTool(System.getenv("OPENAI_API_KEY")));
```

| 工具 | 能力 |
|------|------|
| `DashScopeMultiModalTool` | 文生图、图生文、文生语音、语音转文字 |
| `OpenAIMultiModalTool` | 文生图、图片编辑、图片变体、图生文、文生语音、语音转文字 |

### 子智能体工具

可以将智能体注册为工具，供其他智能体调用。详见 [Agent as Tool](../multi-agent/agent-as-tool.md)。

## AgentTool 接口

需要精细控制时，直接实现接口：

```java
public class CustomTool implements AgentTool {
    @Override
    public String getName() { return "custom_tool"; }

    @Override
    public String getDescription() { return "自定义工具"; }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "查询")
            ),
            "required", List.of("query")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String query = (String) param.getInput().get("query");
        return Mono.just(ToolResultBlock.text("结果：" + query));
    }
}
```

## 配置选项

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .parallel(true)                    // 并行执行多个工具
    .allowToolDeletion(false)          // 禁止删除工具
    .executionConfig(ExecutionConfig.builder()
        .timeout(Duration.ofSeconds(30))
        .build())
    .build());
```

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `parallel` | 是否并行执行多个工具 | `true` |
| `allowToolDeletion` | 是否允许删除工具 | `true` |
| `executionConfig.timeout` | 工具执行超时时间 | 5 分钟 |

## 元工具

让智能体自主管理工具组：

```java
toolkit.registerMetaTool();
// Agent 可调用 "reset_equipped_tools" 激活/停用工具组
```

当工具组较多时，可让智能体根据任务需求自主选择激活哪些工具组。
