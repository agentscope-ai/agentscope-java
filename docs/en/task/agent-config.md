# Agent Configuration

## Overview

Agent is the core abstraction of the AgentScope framework, representing an intelligent entity with autonomous decision-making capabilities. It organically integrates the reasoning capabilities of Large Language Models (LLMs), memory systems, tool invocation, and other functions, enabling developers to build AI applications with perception, thinking, and action capabilities.


A complete Agent consists of the following core components:

- **Model**: Provides language understanding and generation capabilities, serving as the Agent's "brain"
- **Memory**: Stores conversation history and contextual information, giving the Agent "memory"
- **Toolkit**: Empowers the Agent to perform external operations such as API calls, database queries, etc.
- **System Prompt**: Defines the Agent's identity, role, and behavioral norms
- **Hook**: Provides event-driven extension mechanisms for monitoring and customizing Agent behavior


---

## Agent Types

### ReActAgent (Recommended)

**Reasoning + Acting**, a general-purpose Agent combining reasoning and tool execution.

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful assistant")
    .model(model)
    .memory(memory)
    .toolkit(toolkit)
    .build();
```

**Use Cases**:
- Complex tasks requiring tool invocation
- Multi-turn conversation applications
- Problem solving requiring reasoning capabilities

### UserAgent

An Agent that receives external input (e.g., command line, Web UI).

```java
UserAgent user = UserAgent.builder()
    .name("User")
    .build();

Msg userInput = user.call(null).block();
```

**Use Cases**:
- Command-line interaction applications
- Web UI integration
- Human-AI collaboration scenarios

---

## Core Configuration Options

### 1. Basic Configuration

#### name (Required)

The unique identifier name of the Agent.

```java
.name("Assistant")
```

**Purpose**:
- Message sender identification
- Agent recognition in logging and debugging
- Differentiation when multiple Agents collaborate

#### sysPrompt

System prompt defining the Agent's identity, responsibilities, and behavioral norms.

```java
.sysPrompt("You are a professional customer service assistant, skilled at answering user questions.")
```


---

### 2. Model Configuration

#### model (Required)

The LLM model instance that determines the Agent's language understanding and generation capabilities.

```java
.model(DashScopeChatModel.builder()
    .apiKey(apiKey)
    .modelName("qwen3-max")
    .build())
```

---

### 3. Memory Configuration

#### memory (Recommended)

Stores conversation history, giving the Agent contextual memory.

```java
// In-memory (non-persistent)
.memory(new InMemoryMemory())

// Custom memory (for persistence or special logic)
.memory(new CustomMemory())
```
---

### 4. Tool Configuration

#### toolkit (Optional)

Provides the set of tools the Agent can invoke.

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new MyTools());

.toolkit(toolkit)
```

**Tool Group Management**:

```java
// Create tool groups
toolkit.createToolGroup("basic", "Basic Tools", true);
toolkit.createToolGroup("admin", "Admin Tools", false);

// Register tools to groups
toolkit.registration()
    .tool(new BasicTools())
    .group("basic")
    .apply();

// Dynamically activate/deactivate
toolkit.updateToolGroups(List.of("admin"), true);
```

**Purpose**:
- Empower Agent to perform external operations
- Control permissions through tool groups
- Support dynamic tool activation/deactivation

---

### 5. Execution Parameters

#### maxIters

Maximum number of Agent iterations (reasoning + tool execution loop).

```java
.maxIters(10)   // Maximum 10 loops (default)
```

**Execution Flow**:
```
User Input → [Reasoning Phase → Tool Execution Phase] × N times → Final Response
```

Each completion of "reasoning + tool execution" counts as one iteration.

#### checkRunning

Controls whether to check if the Agent is already running before accepting a new call.

```java
.checkRunning(true)   // Default: prevent concurrent calls
.checkRunning(false)  // Allow concurrent calls
```

**Default Value**: `true`

**Behavior**:
- When `true` (default): If `call()` is invoked while the Agent is still processing a previous request, an `IllegalStateException` is thrown with the message "Agent is still running, please wait for it to finish"
- When `false`: Allows concurrent `call()` invocations without checking the running state

**Use Cases**:
- `checkRunning=true` (default): Suitable for most scenarios, prevents state corruption from concurrent execution
- `checkRunning=false`:
  - Stateless Agents that don't maintain conversation state
  - Scenarios requiring concurrent request processing
  - Performance testing or load testing

**Example**:
```java
// Default behavior - prevents concurrent calls
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .build();

// Allow concurrent calls
ReActAgent concurrentAgent = ReActAgent.builder()
    .name("ConcurrentAssistant")
    .model(model)
    .checkRunning(false)
    .build();
```

**Notes**:
- When `checkRunning=false`, ensure the Agent implementation is thread-safe or stateless
- Memory and context state may become inconsistent with concurrent calls
- Consider using separate Agent instances for true concurrent processing

#### modelExecutionConfig

Execution configuration for model calls, controlling timeout and retry behavior.

```java
// Custom model execution configuration
ExecutionConfig modelConfig = ExecutionConfig.builder()
    .timeout(Duration.ofMinutes(2))       // Timeout: 2 minutes
    .maxAttempts(5)                       // Max attempts: 5 (including initial)
    .initialBackoff(Duration.ofSeconds(2)) // Initial backoff: 2 seconds
    .maxBackoff(Duration.ofSeconds(30))   // Max backoff: 30 seconds
    .backoffMultiplier(2.0)               // Backoff multiplier: exponential
    .retryOn(error -> {                   // Custom retry conditions
        return error instanceof TimeoutException
            || error.getMessage().contains("rate limit");
    })
    .build();

.modelExecutionConfig(modelConfig)
```

**Implementation Logic**:

In the `ReActAgent`'s reasoning phase (ReasoningPipeline), this configuration is injected into `GenerateOptions`:

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

**Default Configuration** (ExecutionConfig.MODEL_DEFAULTS):
- Timeout: 5 minutes
- Max attempts: 3 (initial + 2 retries)
- Initial backoff: 1 second
- Max backoff: 10 seconds
- Backoff multiplier: 2.0 (exponential)
- Retry condition: all errors

**Use Cases**:
- Adjust model API timeout
- Configure retry strategy (unstable network scenarios)
- Retry for specific errors
- Control backoff strategy

#### toolExecutionConfig

Execution configuration for tool calls, controlling tool execution timeout and retry behavior.

```java
// Custom tool execution configuration
ExecutionConfig toolConfig = ExecutionConfig.builder()
    .timeout(Duration.ofSeconds(30))  // Tool execution timeout: 30 seconds
    .maxAttempts(1)                   // No retry by default (tools often have side effects)
    .build();

.toolExecutionConfig(toolConfig)
```

**Implementation Logic**:

In the `ReActAgent`'s execution phase (ActingPipeline), this configuration is passed when calling `toolkit.callTools()`:

```548:554:src/main/java/io/agentscope/core/ReActAgent.java
            return toolkit.callTools(
                            toolCalls, toolExecutionConfig, ReActAgent.this, toolExecutionContext)
                    .flatMapMany(responses -> processToolResults(toolCalls, responses))
                    .then()
                    .then(checkInterruptedAsync());
        }
```

**Default Configuration** (ExecutionConfig.TOOL_DEFAULTS):
- Timeout: 5 minutes
- Max attempts: 1 (no retry)

**Notes**:
- Tool calls are generally not recommended for retry due to potential side effects (e.g., database writes, sending emails)
- If retry is needed, ensure tools are idempotent
- Increase timeout for long-running tools

**Configuration Merging**:

`ExecutionConfig` supports parameter-level configuration merging:

```java
// Priority: per-request > agent-level > component-defaults > system-defaults
ExecutionConfig effective = ExecutionConfig.mergeConfigs(
    perRequestConfig,
    ExecutionConfig.mergeConfigs(agentConfig, ExecutionConfig.MODEL_DEFAULTS)
);
```

### 6. Hook Configuration

#### hook / hooks

Event listeners for monitoring and extending Agent behavior.

```java
// Single Hook
.hook(new LoggingHook())

// Multiple Hooks
.hooks(List.of(
    new LoggingHook(),
    new StudioMessageHook(client),
    new MetricsHook()
))
```

---

### 7. Structured Output

#### structuredOutputReminder

Reminder mode for structured output.

```java
// Mode 1: Force tool call (recommended)
.structuredOutputReminder(StructuredOutputReminder.TOOL_CHOICE)

// Mode 2: Prompt guidance
.structuredOutputReminder(StructuredOutputReminder.PROMPT)
```

---

### 8. Tool Execution Context

#### toolExecutionContext

Hidden context object passed to tools.

```java
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user-123"))
    .register(new DatabaseContext(db))
    .build();

.toolExecutionContext(context)
```

**Purpose**:
- Pass user identity information
- Provide database connections
- Inject configuration objects
- Pass request context

**Usage in Tools**:
```java
@Tool
public String getTool(
        @ToolParam(name = "query") String query,
        UserContext ctx  // Auto-injected
) {
    return "Query from user " + ctx.getUserId();
}
```

---

### 9. Plan Management (PlanNotebook)

#### planNotebook

`PlanNotebook` provides structured planning capabilities for Agents, suitable for complex multi-step tasks. It allows Agents to create, modify, and track plans through tool functions, and automatically injects contextual hints through the Hook mechanism.

**Core Features**:
- **Plan Management**: Create, revise, and complete multi-subtask plans
- **Auto Hint Injection**: Automatically inject contextual hints before each reasoning step
- **State Tracking**: Track subtask states (todo/in_progress/done/abandoned)
- **Historical Plans**: Store and recover historical plans

**Configuration Method 1: Quick Enable (Default Configuration)**

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .enablePlan()  // Enable plan functionality with default configuration
    .build();
```

**Configuration Method 2: Custom Configuration**

```java
// Custom PlanNotebook configuration
PlanNotebook planNotebook = PlanNotebook.builder()
    .planToHint(new DefaultPlanToHint())     // Plan-to-hint strategy
    .storage(new InMemoryPlanStorage())      // Plan storage backend (can be changed to persistent storage)
    .maxSubtasks(15)                         // Max 15 subtasks per plan
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .planNotebook(planNotebook)  // Use custom configuration
    .build();
```

---

### 10. Formatter Configuration

Formatter is responsible for converting between AgentScope format and model API format.

```java
.model(DashScopeChatModel.builder()
    .formatter(new DashScopeChatFormatter())
    .build())
```

**Formatters for Different Models**:

```java
// DashScope
.formatter(new DashScopeChatFormatter())

// OpenAI
.formatter(new OpenAIChatFormatter())

// Anthropic
.formatter(new AnthropicChatFormatter())
```

Generally, there's no need to explicitly specify; the model will automatically select the appropriate Formatter.

---

### 11. Skill Configuration

#### skillBox (Optional)

Provides the set of skills available to the Agent. It allows the Agent to load skills through tool functions and automatically injects skill hints via the Hook mechanism.

```java
SkillBox skillBox = new SkillBox();
.skillBox(skillBox)
```

**Purpose**:
- Empower Agent to use skills
- Control skill loading and usage through the skill set
- Support dynamic loading and unloading of skills

---

## Comprehensive Configuration Example

The following example demonstrates the complete usage of all core configuration options:

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
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;

/**
 * Comprehensive Agent Configuration Example
 */
public class ComprehensiveAgentExample {

    // 1. Define tool classes
    public static class WeatherTools {
        @Tool(description = "Get weather information for a specified city")
        public String getWeather(
                @ToolParam(name = "city", description = "City name") String city,
                UserContext userCtx  // Auto-injected
        ) {
            return String.format("User %s queried: Weather in %s is sunny, 25°C",
                    userCtx.getUserId(), city);
        }
    }

    public static class CalculatorTools {
        @Tool(description = "Calculate the sum of two numbers")
        public double add(
                @ToolParam(name = "a", description = "First number") double a,
                @ToolParam(name = "b", description = "Second number") double b) {
            return a + b;
        }
    }

    // 2. Define context class
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

    // 3. Define custom Hook
    public static class LoggingHook implements Hook {
        @Override
        public Mono<HookEvent> onEvent(HookEvent event) {
            System.out.println("[Hook] Event: " + event.getType() +
                    ", Agent: " + event.getAgent().getName());
            return Mono.just(event);
        }
    }

    // 4. Define structured output data class
    public static class CityWeather {
        public String city;
        public String weather;
        public Integer temperature;

        public CityWeather() {}
    }

    // 5. Define skill class
    public static class WeatherSkill extends AgentSkill {
        public WeatherSkill() {
            super("weather", "weather", "weather", null);
        }
    }

    public static void main(String[] args) {
        // ============================================================
        // Step 1: Configure tools and tool groups
        // ============================================================

        Toolkit toolkit = new Toolkit();

        // Create tool groups
        toolkit.createToolGroup("basic", "Basic Tool Group", true);
        toolkit.createToolGroup("advanced", "Advanced Tool Group", false);

        // Register tools to different groups
        toolkit.registration()
                .tool(new WeatherTools())
                .group("basic")
                .apply();

        toolkit.registration()
                .tool(new CalculatorTools())
                .group("advanced")
                .apply();

        // ============================================================
        // Step 2: Configure tool execution context
        // ============================================================

        ToolExecutionContext toolContext = ToolExecutionContext.builder()
                .register(new UserContext("user-abc-123", "admin"))
                .build();

        // ============================================================
        // Step 3: Configure execution strategy (timeout and retry)
        // ============================================================

        // Model execution configuration
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

        // Tool execution configuration
        ExecutionConfig toolExecutionConfig = ExecutionConfig.builder()
                .timeout(Duration.ofSeconds(60))
                .maxAttempts(1)
                .build();

        // ============================================================
        // Step 4: Configure Hooks
        // ============================================================

        List<Hook> hooks = List.of(
                new LoggingHook()
                // Can add more Hooks, such as StudioMessageHook
        );

        // ============================================================
        // Step 5: Configure Skills
        // ============================================================

        SkillBox skillBox = new SkillBox();
        skillBox.registerSkill(new WeatherSkill());

        // ============================================================
        // Step 6: Configure Agent (Complete Configuration)
        // ============================================================

        ReActAgent agent = ReActAgent.builder()
                // 1. Basic configuration
                .name("Smart Assistant")
                .sysPrompt("""
                You are a smart assistant with the following capabilities:
                - Query weather information
                - Perform mathematical calculations
                
                Responsibilities:
                - Accurately understand user needs
                - Properly use tools to complete tasks
                - Answer in concise language
                
                Limitations:
                - Do not fabricate information
                - Be honest when uncertain
                """)

                // 2. Model configuration
                .model(DashScopeChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        .modelName("qwen3-max")
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

                // 3. Memory configuration
                .memory(new InMemoryMemory())

                // 4. Tool configuration
                .toolkit(toolkit)
                .toolExecutionContext(toolContext)

                // 5. Execution parameters
                .maxIters(10)
                .modelExecutionConfig(modelExecutionConfig)
                .toolExecutionConfig(toolExecutionConfig)

                // 6. Hook configuration
                .hooks(hooks)

                // 7. Structured output
                .structuredOutputReminder(StructuredOutputReminder.TOOL_CHOICE)

                // 8. Skill configuration
                .skillBox(skillBox)

                .build();

        // ============================================================
        // Step 7: Use Agent
        // ============================================================

        try {
            // Example 1: Basic conversation
            System.out.println("=== Example 1: Basic Conversation ===");
            Msg msg1 = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("Hello! Please introduce yourself.")
                            .build())
                    .build();

            Msg response1 = agent.call(msg1).block();
            System.out.println("Response: " + response1.getTextContent() + "\n");

            // Example 2: Tool invocation (basic tool group is activated)
            System.out.println("=== Example 2: Query Weather Using Tools ===");
            Msg msg2 = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("What's the weather in Beijing?")
                            .build())
                    .build();

            Msg response2 = agent.call(msg2).block();
            System.out.println("Response: " + response2.getTextContent() + "\n");

            // Example 3: Dynamically activate advanced tool group
            System.out.println("=== Example 3: Activate Advanced Tool Group and Use Calculator ===");
            toolkit.updateToolGroups(List.of("advanced"), true);

            Msg msg3 = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("Calculate 123.45 + 678.90")
                            .build())
                    .build();

            Msg response3 = agent.call(msg3).block();
            System.out.println("Response: " + response3.getTextContent() + "\n");

            // Example 4: Structured output
            System.out.println("=== Example 4: Structured Output ===");
            Msg msg4 = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("Query Shanghai's weather and return city name, weather condition, and temperature in structured format")
                            .build())
                    .build();

            Msg response4 = agent.call(msg4, CityWeather.class).block();
            CityWeather weatherData = response4.getStructuredData(CityWeather.class);

            System.out.println("Extracted structured data:");
            System.out.println("  City: " + weatherData.city);
            System.out.println("  Weather: " + weatherData.weather);
            System.out.println("  Temperature: " + weatherData.temperature + "°C");
            System.out.println();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

---

For detailed parameter configuration, please refer to the corresponding documentation.

