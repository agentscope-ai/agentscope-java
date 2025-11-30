# 工具（Tool）

工具系统使智能体能够突破纯文本生成的限制，执行诸如 API 调用、数据库查询、文件操作等外部操作。

---

## 核心特性

- **基于注解**：使用 `@Tool` 和 `@ToolParam` 快速定义工具
- **响应式编程**：原生支持 `Mono`/`Flux` 异步执行
- **自动 Schema**：自动生成 JSON Schema 供 LLM 理解
- **工具组**：动态激活/停用工具集合
- **预设参数**：隐藏敏感参数（如 API Key）
- **并行执行**：支持并行调用多个工具
- **MCP 支持**：集成 Model Context Protocol

---

## 快速开始

### 1. 定义工具

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

> **重要**：`@ToolParam` 的 `name` 属性是必需的，因为 Java 默认不保留参数名。

### 2. 注册并使用

```java
// 创建工具集
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

// 创建 Agent
ReActAgent agent = ReActAgent.builder()
    .name("助手")
    .model(model)
    .toolkit(toolkit)
    .sysPrompt("你是一个有用的助手，可以查询天气信息。")
    .build();

// 使用
Msg query = Msg.builder()
    .role(MsgRole.USER)
    .textContent("上海的天气怎么样？")
    .build();

Msg response = agent.call(query).block();
```

---

## 工具注册

### 注解方式（推荐）

```java
public class BasicTools {

    // 多参数工具
    @Tool(description = "计算两个数的和")
    public int add(
            @ToolParam(name = "a", description = "第一个数") int a,
            @ToolParam(name = "b", description = "第二个数") int b) {
        return a + b;
    }

    // 异步工具
    @Tool(description = "异步搜索")
    public Mono<String> searchWeb(
            @ToolParam(name = "query", description = "搜索查询") String query) {
        return Mono.delay(Duration.ofSeconds(1))
            .map(ignored -> "搜索结果：" + query);
    }
}

// 注册
toolkit.registerTool(new BasicTools());
```

### AgentTool 接口方式

当需要精细控制时，可直接实现 `AgentTool` 接口：

```java
public class CustomTool implements AgentTool {

    @Override
    public String getName() {
        return "custom_tool";
    }

    @Override
    public String getDescription() {
        return "自定义工具";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "查询内容")
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

### Builder API

```java
// 带工具组
toolkit.registration()
    .tool(new WeatherService())
    .group("weather_group")
    .apply();

// 带预设参数
toolkit.registration()
    .tool(new APIService())
    .presetParameters(Map.of(
        "callAPI", Map.of("apiKey", System.getenv("API_KEY"))
    ))
    .apply();
```

---

## 工具组管理

### 为什么需要工具组？

- **场景化管理**：不同场景激活不同工具集
- **权限控制**：限制用户只能使用特定工具
- **性能优化**：减少 LLM 看到的工具数量

### 基础操作

```java
// 1. 创建工具组
toolkit.createToolGroup("basic", "基础工具", true);
toolkit.createToolGroup("admin", "管理员工具", false);

// 2. 注册工具到组
toolkit.registration()
    .tool(new BasicTools())
    .group("basic")
    .apply();

// 3. 动态激活/停用
toolkit.updateToolGroups(List.of("admin"), true);   // 激活
toolkit.updateToolGroups(List.of("basic"), false);  // 停用

// 4. 查询状态
List<String> activeGroups = toolkit.getActiveGroups();
```

### 场景化示例

```java
// 根据用户角色动态切换工具
String userRole = getCurrentUserRole();

switch (userRole) {
    case "guest":
        toolkit.updateToolGroups(List.of("guest"), true);
        toolkit.updateToolGroups(List.of("user", "admin"), false);
        break;
    case "user":
        toolkit.updateToolGroups(List.of("guest", "user"), true);
        toolkit.updateToolGroups(List.of("admin"), false);
        break;
    case "admin":
        toolkit.updateToolGroups(List.of("guest", "user", "admin"), true);
        break;
}
```

---

## 预设参数

### 概述

**预设参数**在工具执行时自动注入，但**不会**出现在 LLM 可见的 Schema 中，适用于：

- **敏感信息**：API Key、密码
- **上下文信息**：用户 ID、会话 ID
- **固定配置**：服务器地址、区域

### 使用方法

```java
// 定义工具
public class EmailService {
    @Tool(description = "发送邮件")
    public String sendEmail(
            @ToolParam(name = "to", description = "收件人") String to,
            @ToolParam(name = "subject", description = "主题") String subject,
            @ToolParam(name = "apiKey", description = "API Key") String apiKey,
            @ToolParam(name = "from", description = "发件人") String from) {
        return String.format("已从 %s 发送到 %s", from, to);
    }
}

// 注册时设置预设参数
Map<String, Map<String, Object>> presetParams = Map.of(
    "sendEmail", Map.of(
        "apiKey", System.getenv("EMAIL_API_KEY"),
        "from", "noreply@example.com"
    )
);

toolkit.registration()
    .tool(new EmailService())
    .presetParameters(presetParams)
    .apply();
```

**效果**：LLM 只看到 `to` 和 `subject`，`apiKey` 和 `from` 自动注入。

### 运行时更新

```java
// 用户登录后更新用户上下文
toolkit.updateToolPresetParameters("uploadFile", Map.of(
    "userId", userId,
    "sessionId", sessionId
));
```

### 参数优先级

```
LLM 提供的参数 > 预设参数
```

LLM 可以覆盖预设参数（如果需要）。

---

## 工具执行上下文

### 概述

**工具执行上下文**提供类型安全的方式传递自定义对象，而不暴露在 Schema 中。

### 与预设参数的区别

| 特性 | 预设参数 | 执行上下文 |
|------|---------|-----------|
| 传递方式 | Key-Value Map | 类型化对象 |
| 注入方式 | 按工具名匹配 | 按类型自动注入 |
| 类型安全 | 运行时转换 | 编译期检查 |

### 使用方法

```java
// 1. 定义上下文类
public class UserContext {
    private final String userId;
    private final String role;

    public UserContext(String userId, String role) {
        this.userId = userId;
        this.role = role;
    }

    public String getUserId() { return userId; }
    public String getRole() { return role; }
}

// 2. 注册到 Agent
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user-123", "admin"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .toolExecutionContext(context)
    .build();

// 3. 在工具中使用
@Tool(description = "获取用户信息")
public String getUserInfo(
        @ToolParam(name = "infoType") String infoType,
        UserContext context  // 自动注入，无需 @ToolParam
) {
    return String.format("用户 %s (角色: %s) 的信息",
        context.getUserId(), context.getRole());
}
```

### 多类型上下文

```java
// 注册多个上下文
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext(...))
    .register(new DatabaseContext(...))
    .register(new LoggingContext(...))
    .build();

// 工具自动注入需要的上下文
@Tool
public String tool(
        @ToolParam(name = "query") String query,
        UserContext userCtx,
        DatabaseContext dbCtx) {
    // 使用多个上下文
}
```

---

## 内置工具

AgentScope 提供了一系列开箱即用的内置工具，帮助 Agent 执行常见任务。

### 文件操作工具

文件操作工具包（`io.agentscope.core.tool.file`）提供了读取和写入文本文件的能力。

#### 1. 查看文件内容

`ReadFileTool` 提供查看文本文件内容的功能：

```java
import io.agentscope.core.tool.file.ReadFileTool;

// 注册工具
toolkit.registerTool(new ReadFileTool());
```

**功能特性：**

- 查看整个文件内容
- 查看指定行范围（如：`1,100` 查看前 100 行）
- 支持负数索引从末尾查看（如：`-100,-1` 查看最后 100 行）
- 自动添加行号

**使用示例：**

```java
// Agent 可以这样调用：
// "请查看 config.properties 文件的内容"
// "显示 Main.java 的前 50 行"
// "查看日志文件的最后 100 行"
```

**工具 Schema：**

- **工具名**：`view_text_file`
- **参数**：
  - `file_path`（必需）：目标文件路径
  - `ranges`（可选）：查看的行范围，格式 `"start,end"` 或 `"[start,end]"`
    - 示例：`"1,100"` - 查看第 1-100 行
    - 示例：`"-50,-1"` - 查看最后 50 行

#### 2. 写入文件内容

`WriteFileTool` 提供创建、覆盖、替换文件内容的功能：

```java
import io.agentscope.core.tool.file.WriteFileTool;

// 注册工具
toolkit.registerTool(new WriteFileTool());
```

**功能特性：**

- **创建新文件**：当文件不存在时自动创建
- **覆盖整个文件**：不指定范围时覆盖全部内容
- **替换指定行范围**：精确替换某些行
- **插入新内容**：在指定行号插入新内容，不删除原有内容

**工具 Schema：**

##### `write_text_file` - 写入/替换内容

- **参数**：
  - `file_path`（必需）：目标文件路径
  - `content`（必需）：要写入的内容
  - `ranges`（可选）：替换的行范围，格式 `"start,end"`
    - 不指定：覆盖整个文件
    - 指定范围：只替换该范围的行

##### `insert_text_file` - 插入内容

- **参数**：
  - `file_path`（必需）：目标文件路径
  - `content`（必需）：要插入的内容
  - `line_number`（必需）：插入位置的行号（从 1 开始）

**使用场景：**

```java
// Agent 可以执行：
// "创建一个新的 README.md 文件，内容是..."
// "将 config.yaml 的第 10-15 行替换为..."
// "在 Main.java 的第 50 行插入一个新方法..."
// "重写整个 settings.json 文件"
```

#### 完整示例：文件操作 Agent

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;

public class FileToolExample {
    public static void main(String[] args) {
        // 创建工具包
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReadFileTool());
        toolkit.registerTool(new WriteFileTool());

        // 创建 Agent
        ReActAgent agent = ReActAgent.builder()
            .name("文件助手")
            .model(model)
            .toolkit(toolkit)
            .sysPrompt("你是一个文件管理助手，可以查看和编辑文本文件。")
            .build();

        // 测试文件操作
        Msg query = Msg.builder()
            .role(MsgRole.USER)
            .textContent("请创建一个 hello.txt 文件，内容是 'Hello World'")
            .build();

        Msg response = agent.call(query).block();
        System.out.println(response.getTextContent());
    }
}
```

#### 注意事项

- **文件路径**：支持相对路径和绝对路径
- **文件编码**：默认使用 UTF-8 编码
- **行号索引**：从 1 开始（不是 0）
- **错误处理**：文件不存在、权限不足等错误会返回友好的错误消息
- **自动展示**：写入后会自动展示修改区域周围的内容，便于 Agent 验证结果

---

## 高级特性

### 1. 并行工具执行

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .parallel(true)  // 启用并行
    .build());
```

多个工具并行执行，显著提升效率。

### 2. 超时和重试

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .executionConfig(ExecutionConfig.builder()
        .timeout(Duration.ofSeconds(10))
        .maxRetries(2)
        .build())
    .build());
```

### 3. 流式工具响应

```java
@Tool
public ToolResultBlock generateData(
        @ToolParam(name = "count") int count,
        ToolEmitter emitter  // 发送流式数据
) {
    for (int i = 0; i < count; i++) {
        emitter.emit(ToolResultBlock.text("进度 " + i));
    }
    return ToolResultBlock.text("完成");
}
```

### 4. 元工具

允许 Agent 自主管理工具组：

```java
toolkit.registerMetaTool();

// Agent 可以调用 "reset_equipped_tools" 激活工具组
```

### 5. 禁止工具删除

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .allowToolDeletion(false)
    .build());
```


## 完整示例

```java
package io.agentscope.tutorial;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

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

        // 创建 Agent
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
                .content(TextBlock.builder()
                        .text("北京的天气怎么样？另外，15 + 27 等于多少？")
                        .build())
                .build();

        Msg response = agent.call(question).block();
        System.out.println("问题: " + question.getTextContent());
        System.out.println("答案: " + response.getTextContent());
    }
}
```

---

## 更多资源

- **示例代码**：[ToolCallingExample.java](../../examples/src/main/java/io/agentscope/examples/ToolCallingExample.java)
- **Hook 文档**：[hook.md](./hook.md) - 监控工具执行
- **MCP 文档**：[mcp.md](./mcp.md) - 集成外部工具

---

**问题反馈**：[GitHub Issues](https://github.com/modelscope/agentscope/issues)
