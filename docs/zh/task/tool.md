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

```text
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

文件操作工具包（`io.agentscope.core.tool.file`）提供读写文本文件的能力。

**快速使用：**

```java
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;

// 推荐注册方式（请始终指定安全的 baseDir）
toolkit.registerTool(new ReadFileTool("/safe/workspace"));
toolkit.registerTool(new WriteFileTool("/safe/workspace"));

// ⚠️ 不建议使用无参构造函数，可能导致任意文件访问风险
// toolkit.registerTool(new ReadFileTool());
// toolkit.registerTool(new WriteFileTool());
```

**主要功能：**

| 工具 | 方法 | 功能说明 |
|------|------|---------|
| `ReadFileTool` | `view_text_file` | 查看文件，支持行范围（如 `1,100`）和负索引（如 `-50,-1` 查看最后50行） |
| `WriteFileTool` | `write_text_file` | 创建/覆盖/替换文件内容，可指定行范围 |
| `WriteFileTool` | `insert_text_file` | 在指定行插入内容 |

**安全特性：**

构造函数支持 `baseDir` 参数限制文件访问范围，防止路径遍历攻击：

```java
// 为不同 Agent 创建隔离工作空间
public Toolkit createAgentToolkit(String agentId) {
    String workspace = "/workspaces/agent_" + agentId;
    Toolkit toolkit = new Toolkit();
    toolkit.registerTool(new ReadFileTool(workspace));
    toolkit.registerTool(new WriteFileTool(workspace));
    return toolkit;
}
```

**注意：** UTF-8 编码，行号从 1 开始，生产环境建议设置 `baseDir`

### Shell 命令工具

Shell 命令工具（`io.agentscope.core.tool.coding`）提供执行脚本命令的能力。

**快速使用：**

```java
import io.agentscope.core.tool.coding.ShellCommandTool;

toolkit.registerTool(new ShellCommandTool());
```

**主要功能：**

| 工具 | 方法 | 功能说明 |
|------|------|----------|
| `ShellCommandTool` | `execute_shell_command` | 执行 Shell 命令并返回执行结果 |

**特性：**

- **超时控制**：单位是秒，传入 `null` 则使用默认的 300 秒超时
- **输出捕获**：自动捕获 stdout、stderr 和返回代码
- **跨平台支持**：自动适配 Windows/Linux/macOS
- **异步执行**：基于 Reactor Mono 的响应式设计

**使用示例：**

```java
ShellCommandTool tool = new ShellCommandTool();

// 使用默认超时（300秒）
Mono<ToolResultBlock> result1 = tool.executeShellCommand("echo 'Hello, World!'", null);

// 指定超时时间（10秒）
Mono<ToolResultBlock> result2 = tool.executeShellCommand("ls -la /tmp", 10);
```

**输出格式：**

执行结果以 XML 标签格式返回：
```xml
<returncode>0</returncode>
<stdout>命令的标准输出</stdout>
<stderr>命令的标准错误输出</stderr>
```

**安全提示：**

⚠️ 此工具执行任意 Shell 命令，仅应在受信任的环境中使用。生产环境建议实施命令白名单、沙箱隔离等安全措施。

### 多模态工具

**快速使用：**

```java
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;
import io.agentscope.core.tool.multimodal.OpenAIMultiModalTool;

// 推荐注册方式
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
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .parallel(true)  // 启用并行
    .build());
```

多个工具并行执行，显著提升效率。

### 2. 超时和重试

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
