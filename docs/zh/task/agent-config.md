# Agent 配置（Agent Configuration）

## 概述

Agent 是 AgentScope 框架的核心抽象，代表一个具有自主决策能力的智能体。它将大语言模型（LLM）的推理能力、记忆系统、工具调用等功能有机整合在一起，使得开发者能够构建具有感知、思考、行动能力的 AI 应用。


一个完整的 Agent 由以下核心组件构成：

- **Model（模型）**：提供语言理解和生成能力，是 Agent 的"大脑"
- **Memory（记忆）**：存储对话历史和上下文信息，使 Agent 具有"记忆"
- **Toolkit（工具集）**：赋予 Agent 执行外部操作的能力，如 API 调用、数据库查询等
- **System Prompt（系统提示词）**：定义 Agent 的身份、角色和行为规范
- **Hook（钩子）**：提供事件驱动的扩展机制，用于监控和定制 Agent 行为


---

## Agent 类型

### ReActAgent（推荐）

**Reasoning + Acting**，结合推理与工具执行的通用 Agent。

```java
ReActAgent agent = ReActAgent.builder()
    .name("助手")
    .sysPrompt("你是一个有帮助的助手")
    .model(model)
    .memory(memory)
    .toolkit(toolkit)
    .build();
```

**适用场景**：
- 需要调用工具的复杂任务
- 多轮对话应用
- 需要推理能力的问题解决

### UserAgent

接收外部输入的 Agent（如命令行、Web UI）。

```java
UserAgent user = UserAgent.builder()
    .name("用户")
    .build();

Msg userInput = user.call(null).block();
```

**适用场景**：
- 命令行交互应用
- Web UI 集成
- 人机协作场景

---

## 核心配置项详解

### 1. 基础配置

#### name（必需）

Agent 的唯一标识名称。

```java
.name("助手")
```

**用途**：
- 消息的发送者标识
- 日志和调试中的 Agent 识别
- 多 Agent 协作时的区分

#### sysPrompt 

系统提示词，定义 Agent 的身份、职责和行为规范。

```java
.sysPrompt("你是一个专业的客服助手，擅长解答用户问题。")
```


---

### 2. 模型配置

#### model（必需）

LLM 模型实例，决定 Agent 的语言理解和生成能力。

```java
.model(DashScopeChatModel.builder()
    .apiKey(apiKey)
    .modelName("qwen-max")
    .build())
```

---

### 3. 记忆配置

#### memory（推荐）

存储对话历史，使 Agent 具有上下文记忆。

```java
// 内存记忆（不持久化）
.memory(new InMemoryMemory())

// 自定义记忆（如需持久化或特殊逻辑）
.memory(new CustomMemory())
```
---

### 4. 工具配置

#### toolkit（可选）

提供 Agent 可调用的工具集。

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new MyTools());

.toolkit(toolkit)
```

**工具组管理**：

```java
// 创建工具组
toolkit.createToolGroup("basic", "基础工具", true);
toolkit.createToolGroup("admin", "管理工具", false);

// 注册工具到组
toolkit.registration()
    .tool(new BasicTools())
    .group("basic")
    .apply();

// 动态激活/停用
toolkit.updateToolGroups(List.of("admin"), true);
```

**用途**：
- 赋予 Agent 执行外部操作的能力
- 通过工具组控制权限
- 支持动态激活/停用工具

---

### 5. 执行参数

#### maxIters

Agent 最大迭代次数（推理+工具执行循环）。

```java
.maxIters(10)   // 最多执行 10 次循环（默认值）
```

**执行流程**：
```
用户输入 → [推理阶段 → 工具执行阶段] × N 次 → 最终回复
```

每完成一次「推理+工具执行」算作一次迭代。

#### modelExecutionConfig

模型调用的执行配置，控制超时和重试行为。

```java
// 自定义模型调用配置
ExecutionConfig modelConfig = ExecutionConfig.builder()
    .timeout(Duration.ofMinutes(2))       // 超时时间：2 分钟
    .maxAttempts(5)                       // 最大尝试次数：5 次（含初始调用）
    .initialBackoff(Duration.ofSeconds(2)) // 初始退避时间：2 秒
    .maxBackoff(Duration.ofSeconds(30))   // 最大退避时间：30 秒
    .backoffMultiplier(2.0)               // 退避倍数：指数退避
    .retryOn(error -> {                   // 自定义重试条件
        return error instanceof TimeoutException 
            || error.getMessage().contains("rate limit");
    })
    .build();

.modelExecutionConfig(modelConfig)
```

**代码实现逻辑**：

在 `ReActAgent` 的推理阶段（ReasoningPipeline），会将此配置注入到 `GenerateOptions` 中：

```128:129:src/main/java/io/agentscope/core/ReActAgent.java
    private final ExecutionConfig modelExecutionConfig;
    private final ExecutionConfig toolExecutionConfig;
```

```366:372:src/main/java/io/agentscope/core/ReActAgent.java
    private GenerateOptions buildGenerateOptions() {
        GenerateOptions.Builder builder = GenerateOptions.builder();
        if (modelExecutionConfig != null) {
            builder.executionConfig(modelExecutionConfig);
        }
        return builder.build();
    }
```

**默认配置**（ExecutionConfig.MODEL_DEFAULTS）：
- 超时：5 分钟
- 最大尝试：3 次（初始 + 2 次重试）
- 初始退避：1 秒
- 最大退避：10 秒
- 退避倍数：2.0（指数退避）
- 重试条件：所有错误

**使用场景**：
- 调整模型 API 的超时时间
- 配置重试策略（网络不稳定场景）
- 针对特定错误进行重试
- 控制退避策略

#### toolExecutionConfig

工具调用的执行配置，控制工具执行的超时和重试行为。

```java
// 自定义工具执行配置
ExecutionConfig toolConfig = ExecutionConfig.builder()
    .timeout(Duration.ofSeconds(30))  // 工具执行超时：30 秒
    .maxAttempts(1)                   // 默认不重试（工具调用通常有副作用）
    .build();

.toolExecutionConfig(toolConfig)
```

**代码实现逻辑**：

在 `ReActAgent` 的执行阶段（ActingPipeline），调用 `toolkit.callTools()` 时传入此配置：

```548:554:src/main/java/io/agentscope/core/ReActAgent.java
            return toolkit.callTools(
                            toolCalls, toolExecutionConfig, ReActAgent.this, toolExecutionContext)
                    .flatMapMany(responses -> processToolResults(toolCalls, responses))
                    .then()
                    .then(checkInterruptedAsync());
        }
```

**默认配置**（ExecutionConfig.TOOL_DEFAULTS）：
- 超时：5 分钟
- 最大尝试：1 次（不重试）

**注意事项**：
- 工具调用通常不建议重试，因为可能有副作用（如写数据库、发送邮件）
- 如果需要重试，请确保工具是幂等的
- 对于长时间运行的工具，适当增加超时时间

**配置合并**：

`ExecutionConfig` 支持参数级别的配置合并：

```java
// 优先级：per-request > agent-level > component-defaults > system-defaults
ExecutionConfig effective = ExecutionConfig.mergeConfigs(
    perRequestConfig,
    ExecutionConfig.mergeConfigs(agentConfig, ExecutionConfig.MODEL_DEFAULTS)
);
```

### 6. Hook 配置

#### hook / hooks

事件监听器，用于监控和扩展 Agent 行为。

```java
// 单个 Hook
.hook(new LoggingHook())

// 多个 Hook
.hooks(List.of(
    new LoggingHook(),
    new StudioMessageHook(client),
    new MetricsHook()
))
```

---

### 7. 结构化输出

#### structuredOutputReminder

结构化输出的提醒模式。

```java
// 模式 1: 强制工具调用（推荐）
.structuredOutputReminder(StructuredOutputReminder.TOOL_CHOICE)

// 模式 2: 提示词引导
.structuredOutputReminder(StructuredOutputReminder.PROMPT)
```

---

### 8. 工具执行上下文

#### toolExecutionContext

传递给工具的隐藏上下文对象。

```java
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user-123"))
    .register(new DatabaseContext(db))
    .build();

.toolExecutionContext(context)
```

**用途**：
- 传递用户身份信息
- 提供数据库连接
- 注入配置对象
- 传递请求上下文

**工具中使用**：
```java
@Tool
public String getTool(
        @ToolParam(name = "query") String query,
        UserContext ctx  // 自动注入
) {
    return "用户 " + ctx.getUserId() + " 的查询";
}
```

---

### 9. 计划管理 (PlanNotebook)

#### planNotebook

`PlanNotebook` 为 Agent 提供结构化的计划管理能力，适用于复杂多步骤任务。它通过提供工具函数让 Agent 创建、修改、跟踪计划，并通过 Hook 机制自动注入上下文提示。

**核心功能**：
- **计划管理**：创建、修订、完成多子任务计划
- **自动提示注入**：在每次推理前自动注入上下文提示
- **状态跟踪**：跟踪子任务状态（todo/in_progress/done/abandoned）
- **历史计划**：存储和恢复历史计划

**配置方式 1：快速启用（使用默认配置）**

```java
ReActAgent agent = ReActAgent.builder()
    .name("助手")
    .model(model)
    .toolkit(toolkit)
    .enablePlan()  // 启用计划功能，使用默认配置
    .build();
```

**配置方式 2：自定义配置**

```java
// 自定义 PlanNotebook 配置
PlanNotebook planNotebook = PlanNotebook.builder()
    .planToHint(new DefaultPlanToHint())     // 计划转提示的策略
    .storage(new InMemoryPlanStorage())      // 计划存储后端（可换成持久化存储）
    .maxSubtasks(15)                         // 单个计划最多 15 个子任务
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("助手")
    .model(model)
    .toolkit(toolkit)
    .planNotebook(planNotebook)  // 使用自定义配置
    .build();
```

---

### 10. Formatter 配置

Formatter 负责在 AgentScope 格式和模型 API 格式之间转换。

```java
.model(DashScopeChatModel.builder()
    .formatter(new DashScopeChatFormatter())
    .build())
```

**不同模型的 Formatter**：

```java
// DashScope
.formatter(new DashScopeChatFormatter())

// OpenAI
.formatter(new OpenAIChatFormatter())

// Anthropic
.formatter(new AnthropicChatFormatter())
```

通常不需要显式指定，模型会自动选择合适的 Formatter。

---


## 综合配置示例

以下示例展示了所有核心配置项的完整用法：

```java
package io.agentscope.tutorial;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.plan.PlanNotebook;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;

/**
 * Agent 核心配置完整示例
 */
public class ComprehensiveAgentExample {
    
    // 1. 定义工具类
    public static class WeatherTools {
        @Tool(description = "获取指定城市的天气信息")
        public String getWeather(
                @ToolParam(name = "city", description = "城市名称") String city,
                UserContext userCtx  // 自动注入上下文
        ) {
            return String.format("用户 %s 查询：%s 的天气为晴天，25°C",
                    userCtx.getUserId(), city);
        }
    }

    public static class CalculatorTools {
        @Tool(description = "计算两个数的和")
        public double add(
                @ToolParam(name = "a", description = "第一个数") double a,
                @ToolParam(name = "b", description = "第二个数") double b) {
            return a + b;
        }
    }

    // 2. 定义上下文类
    public static class UserContext {
        private final String userId;
        private final String role;

        public UserContext(String userId, String role) {
            this.userId = userId;
            this.role = role;
        }

        public String getUserId() { return userId; }
        public String getRole() { return role; }
    }

    // 3. 定义自定义 Hook
    public static class LoggingHook implements Hook {
        @Override
        public Mono<HookEvent> onEvent(HookEvent event) {
            System.out.println("[Hook] 事件: " + event.getType() +
                    ", Agent: " + event.getAgent().getName());
            return Mono.just(event);
        }
    }

    // 4. 定义结构化输出的数据类
    public static class CityWeather {
        public String city;
        public String weather;
        public Integer temperature;
        
        public CityWeather() {}
    }

    public static void main(String[] args) {
        // ============================================================
        // 第一步：配置工具和工具组
        // ============================================================

        Toolkit toolkit = new Toolkit();

        // 创建工具组
        toolkit.createToolGroup("basic", "基础工具组", true);     
        toolkit.createToolGroup("advanced", "高级工具组", false);  

        // 注册工具到不同组
        toolkit.registration()
                .tool(new WeatherTools())
                .group("basic")
                .apply();

        toolkit.registration()
                .tool(new CalculatorTools())
                .group("advanced")
                .apply();

        // ============================================================
        // 第二步：配置工具执行上下文
        // ============================================================

        ToolExecutionContext toolContext = ToolExecutionContext.builder()
                .register(new UserContext("user-abc-123", "admin"))
                .build();

        // ============================================================
        // 第三步：配置执行策略（超时和重试）
        // ============================================================

        // 模型调用执行配置
        ExecutionConfig modelExecutionConfig = ExecutionConfig.builder()
                .timeout(Duration.ofMinutes(3))         
                .maxAttempts(5)                         
                .initialBackoff(Duration.ofSeconds(2))  
                .maxBackoff(Duration.ofSeconds(30))     
                .backoffMultiplier(2.0)                 
                .retryOn(error -> {                     
                    String msg = error.getMessage();
                    return msg != null && (msg.contains("timeout")
                            || msg.contains("rate limit")
                            || msg.contains("503"));
                })
                .build();

        // 工具执行配置
        ExecutionConfig toolExecutionConfig = ExecutionConfig.builder()
                .timeout(Duration.ofSeconds(60))   
                .maxAttempts(1)                    
                .build();


        // ============================================================
        // 第四步：配置 Hook
        // ============================================================

        List<Hook> hooks = List.of(
                new LoggingHook()
                // 可添加更多 Hook，如 StudioMessageHook
        );

        // ============================================================
        // 第六步：配置 Agent（完整配置）
        // ============================================================

        ReActAgent agent = ReActAgent.builder()
                // 1. 基础配置
                .name("智能助手")
                .sysPrompt("""
                你是一个智能助手，拥有以下能力：
                - 查询天气信息
                - 进行数学计算
                
                职责：
                - 准确理解用户需求
                - 合理使用工具完成任务
                - 用简洁的语言回答
                
                限制：
                - 不要编造信息
                - 不确定时请诚实告知
                """)

                // 2. 模型配置
                .model(DashScopeChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        .modelName("qwen-max")                  
                        .stream(true)                            
                        .enableThinking(true)                    
                        .formatter(new DashScopeChatFormatter())
                        .defaultOptions(GenerateOptions.builder()
                                .temperature(0.7)                    
                                .maxTokens(2000)                     
                                .topP(0.9)                           
                                .thinkingBudget(1024)                
                                .build())
                        .build())

                // 3. 记忆配置
                .memory(new InMemoryMemory())

                // 4. 工具配置
                .toolkit(toolkit)
                .toolExecutionContext(toolContext)           

                // 5. 执行参数
                .maxIters(10)                                
                .modelExecutionConfig(modelExecutionConfig)  
                .toolExecutionConfig(toolExecutionConfig)    

                // 6. Hook 配置
                .hooks(hooks)

                // 7. 结构化输出
                .structuredOutputReminder(StructuredOutputReminder.TOOL_CHOICE)

                .build();

        // ============================================================
        // 第七步：使用 Agent
        // ============================================================

        try {
            // 示例 1: 基础对话
            System.out.println("=== 示例 1: 基础对话 ===");
            Msg msg1 = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("你好！请介绍一下你自己。")
                            .build())
                    .build();

            Msg response1 = agent.call(msg1).block();
            System.out.println("回答: " + response1.getTextContent() + "\n");

            // 示例 2: 工具调用（基础工具组已激活）
            System.out.println("=== 示例 2: 使用工具查询天气 ===");
            Msg msg2 = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("北京的天气怎么样？")
                            .build())
                    .build();

            Msg response2 = agent.call(msg2).block();
            System.out.println("回答: " + response2.getTextContent() + "\n");

            // 示例 3: 动态激活高级工具组
            System.out.println("=== 示例 3: 激活高级工具组并使用计算器 ===");
            toolkit.updateToolGroups(List.of("advanced"), true);

            Msg msg3 = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("计算 123.45 + 678.90 等于多少？")
                            .build())
                    .build();

            Msg response3 = agent.call(msg3).block();
            System.out.println("回答: " + response3.getTextContent() + "\n");

            // 示例 4: 结构化输出
            System.out.println("=== 示例 4: 结构化输出 ===");
            Msg msg4 = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("查询上海的天气，并以结构化格式返回城市名、天气状况、温度")
                            .build())
                    .build();

            Msg response4 = agent.call(msg4, CityWeather.class).block();
            CityWeather weatherData = response4.getStructuredData(CityWeather.class);
            
            System.out.println("提取的结构化数据:");
            System.out.println("  城市: " + weatherData.city);
            System.out.println("  天气: " + weatherData.weather);
            System.out.println("  温度: " + weatherData.temperature + "°C");
            System.out.println();
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

---

具体参数的细节配置可以参考对应的文档进行了解与配置。
