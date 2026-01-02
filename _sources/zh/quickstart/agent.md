# 创建 ReAct 智能体

AgentScope 提供了开箱即用的 ReAct 智能体 `ReActAgent` 供开发者使用。

它同时支持以下功能：

- **基础功能**
    - 支持围绕 `reasoning` 和 `acting` 的钩子函数（hooks）
    - 支持结构化输出
- **实时介入（Realtime Steering）**
    - 支持用户中断
    - 支持自定义中断处理
- **工具**
    - 支持同步/异步工具函数
    - 支持流式工具响应
    - 支持并行工具调用
    - 支持 MCP 服务器
- **记忆**
    - 支持智能体自主管理长期记忆
    - 支持"静态"的长期记忆管理

## 创建 ReActAgent

`ReActAgent` 类在其构造函数中暴露了以下参数：

| 参数 | 进一步阅读 | 描述 |
|------|-----------|------|
| `name` (必需) | | 智能体的名称 |
| `sysPrompt` (必需) | | 智能体的系统提示 |
| `model` (必需) | [模型集成](../task/model.md) | 智能体用于生成响应的模型 |
| `toolkit` | [工具系统](../task/tool.md) | 用于注册/调用工具函数的工具模块 |
| `memory` | [记忆管理](../task/memory.md) | 用于存储对话历史的短期记忆 |
| `longTermMemory` | [长期记忆](../task/long-term-memory.md) | 长期记忆 |
| `longTermMemoryMode` | [长期记忆](../task/long-term-memory.md) | 长期记忆的管理模式：`AGENT_CONTROL`（智能体自主控制）、`STATIC_CONTROL`（静态管理）、`BOTH`（两者皆有） |
| `maxIters` | | 智能体生成响应的最大迭代次数（默认：10） |
| `hooks` | [Hook 系统](../task/hook.md) | 用于自定义智能体行为的事件钩子 |
| `modelExecutionConfig` | | 模型调用的超时/重试配置 |
| `toolExecutionConfig` | | 工具调用的超时/重试配置 |

以 DashScope API 为例，我们创建一个智能体对象如下：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.annotation.Tool;
import io.agentscope.core.tool.annotation.ToolParam;

public class QuickStart {
    public static void main(String[] args) {
        // 准备工具
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        // 创建智能体
        ReActAgent jarvis = ReActAgent.builder()
            .name("Jarvis")
            .sysPrompt("你是一个名为 Jarvis 的助手")
            .model(DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen3-max")
                .build())
            .toolkit(toolkit)
            .build();

        // 发送消息
        Msg msg = Msg.builder()
            .textContent("你好！Jarvis，现在几点了？")
            .build();

        Msg response = jarvis.call(msg).block();
        System.out.println(response.getTextContent());
    }
}

// 工具类
class SimpleTools {
    @Tool(name = "get_time", description = "获取当前时间")
    public String getTime(
            @ToolParam(name = "zone", description = "时区，例如：北京") String zone) {
        return java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
```

## 更多配置

### 执行控制

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful assistant.")
    .model(model)
    .maxIters(10)              // 最大迭代次数（默认 10）
    .checkRunning(true)        // 阻止并发调用（默认 true）
    .build();
```

### 超时与重试

```java
ExecutionConfig modelConfig = ExecutionConfig.builder()
    .timeout(Duration.ofMinutes(2))
    .maxAttempts(3)
    .build();

ExecutionConfig toolConfig = ExecutionConfig.builder()
    .timeout(Duration.ofSeconds(30))
    .maxAttempts(1)  // 工具通常不重试
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .modelExecutionConfig(modelConfig)
    .toolExecutionConfig(toolConfig)
    .build();
```

### 工具执行上下文

向工具传递业务上下文（如用户信息），无需暴露给 LLM：

```java
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user-123"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .toolExecutionContext(context)
    .build();

// 工具中自动注入
@Tool(name = "query", description = "查询数据")
public String query(
    @ToolParam(name = "sql") String sql,
    UserContext ctx  // 自动注入，无需 @ToolParam
) {
    return "用户 " + ctx.getUserId() + " 的查询结果";
}
```

### 计划管理

启用 PlanNotebook 支持复杂多步骤任务：

```java
// 快速启用
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .enablePlan()
    .build();

// 自定义配置
PlanNotebook planNotebook = PlanNotebook.builder()
    .maxSubtasks(15)
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .planNotebook(planNotebook)
    .build();
```

## UserAgent

接收外部输入的智能体（如命令行、Web UI）：

```java
UserAgent user = UserAgent.builder()
    .name("用户")
    .build();

Msg userInput = user.call(null).block();
```
