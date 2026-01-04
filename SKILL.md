---
name: agentscope-java
description: Expert Java developer skill for AgentScope Java framework - a reactive, message-driven multi-agent system built on Project Reactor. Use when working with reactive programming, LLM integration, agent orchestration, multi-agent systems, or when the user mentions AgentScope, ReActAgent, Mono/Flux, Project Reactor, or Java agent development. Specializes in non-blocking code, tool integration, hooks, pipelines, and production-ready agent applications.
license: Apache-2.0
compatibility: Designed for Claude Code and Cursor. Requires Java 17+, Maven/Gradle, and familiarity with reactive programming concepts.
metadata:
  framework: AgentScope Java
  language: Java 17+
  paradigm: Reactive Programming
  core-library: Project Reactor
  version: "1.0"
---

When the user asks you to write AgentScope Java code, follow these instructions carefully.

## CRITICAL RULES - NEVER VIOLATE THESE

**🚫 ABSOLUTELY FORBIDDEN:**
1. **NEVER use `.block()` in example code** - This is the #1 mistake. Only use `.block()` in `main()` methods or test code when explicitly creating a runnable example.
2. **NEVER use `Thread.sleep()`** - Use `Mono.delay()` instead.
3. **NEVER use `ThreadLocal`** - Use Reactor Context with `Mono.deferContextual()`.
4. **NEVER hardcode API keys** - Always use `System.getenv()`.
5. **NEVER ignore errors silently** - Always log errors and provide fallback values.
6. **NEVER use wrong import paths** - All models are in `io.agentscope.core.model.*`, NOT `io.agentscope.model.*`.

**✅ ALWAYS DO:**
1. **Use `Mono` and `Flux`** for all asynchronous operations.
2. **Chain operations** with `.map()`, `.flatMap()`, `.then()`.
3. **Use Builder pattern** for creating agents, models, and messages.
4. **Include error handling** with `.onErrorResume()` or `.onErrorReturn()`.
5. **Add logging** with SLF4J for important operations.
6. **Use correct imports**: `import io.agentscope.core.model.DashScopeChatModel;`
7. **Use correct APIs** (many methods don't exist or have changed):
   - `toolkit.registerTool()` NOT `registerObject()`
   - `toolkit.getToolNames()` NOT `getTools()`
   - `event.getToolUse().getName()` NOT `getToolName()`
   - `result.getOutput()` NOT `getContent()` (ToolResultBlock)
   - `event.getToolResult()` NOT `getResult()` (PostActingEvent)
   - `toolUse.getInput()` NOT `getArguments()` (ToolUseBlock)
   - Model builder: NO `temperature()` method, use `defaultOptions(GenerateOptions.builder()...)`
   - Hook events: NO `getMessages()`, `getResponse()`, `getIterationCount()`, `getThinkingBlock()` methods
   - ToolResultBlock: NO `getToolUseName()` method, use `event.getToolUse().getName()` instead
   - ToolResultBlock.getOutput() returns `List<ContentBlock>` NOT `String`, need to convert
   - **@ToolParam format**: MUST use `@ToolParam(name = "x", description = "y")` NOT `@ToolParam(name="x")`

---

## WHEN GENERATING CODE

**FIRST: Identify the context**
- Is this a `main()` method or test code? → `.block()` is allowed (but add a warning comment)
- Is this agent logic, service method, or library code? → `.block()` is **FORBIDDEN**

**For every code example you provide:**
1. Check: Does it use `.block()`? → If yes in non-main/non-test code, **REWRITE IT**.
2. Check: Are all operations non-blocking? → If no, **FIX IT**.
3. Check: Does it have error handling? → If no, **ADD IT**.
4. Check: Are API keys from environment? → If no, **CHANGE IT**.
5. Check: Are imports correct? → If using `io.agentscope.model.*`, **FIX TO** `io.agentscope.core.model.*`.

**Default code structure for agent logic:**
```java
// ✅ CORRECT - Non-blocking, reactive (use this pattern by default)
return model.generate(messages, null, null)
    .map(response -> processResponse(response))
    .onErrorResume(e -> {
        log.error("Operation failed", e);
        return Mono.just(fallbackValue);
    });

// ❌ WRONG - Never generate this in agent logic
String result = model.generate(messages, null, null).block(); // DON'T DO THIS
```

**Only for main() methods (add warning comment):**
```java
public static void main(String[] args) {
    // ⚠️ .block() is ONLY allowed here because this is a main() method
    Msg response = agent.call(userMsg).block();
    System.out.println(response.getTextContent());
}
```

---

## PROJECT SETUP

**When creating a new AgentScope project, use the correct Maven dependencies:**

### Maven Configuration (pom.xml)

**For production use (recommended):**
```xml
<properties>
    <java.version>17</java.version>
</properties>

<dependencies>
    <!-- Use the latest stable release from Maven Central -->
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope</artifactId>
        <version>1.0.5</version>
    </dependency>
</dependencies>
```

**For local development (if working with source code):**
```xml
<properties>
    <agentscope.version>1.0.5</agentscope.version>
    <java.version>17</java.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-core</artifactId>
        <version>${agentscope.version}</version>
    </dependency>
</dependencies>
```

**⚠️ IMPORTANT: Version Selection**
- **Use `agentscope:1.0.5`** for production (stable, from Maven Central)
- **Use `agentscope-core:1.0.5`** only if you're developing AgentScope itself
- **NEVER use version `0.1.0-SNAPSHOT`** - this version doesn't exist

### ⚠️ CRITICAL: Common Dependency Mistakes

**❌ WRONG - These artifacts don't exist:**
```xml
<!-- DON'T use these - they don't exist -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-model-dashscope</artifactId>  <!-- ❌ WRONG -->
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-model-openai</artifactId>  <!-- ❌ WRONG -->
</dependency>
```

**❌ WRONG - These versions don't exist:**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>  <!-- ❌ WRONG - doesn't exist -->
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>0.1.0</version>  <!-- ❌ WRONG - doesn't exist -->
</dependency>
```

**✅ CORRECT - Use the stable release:**
```xml
<!-- For production: use the stable release from Maven Central -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.5</version>  <!-- ✅ CORRECT -->
</dependency>
```

### Available Model Classes (all in agentscope-core)

```java
// DashScope (Alibaba Cloud)
import io.agentscope.core.model.DashScopeChatModel;

// OpenAI
import io.agentscope.core.model.OpenAIChatModel;

// Gemini (Google)
import io.agentscope.core.model.GeminiChatModel;

// Anthropic (Claude)
import io.agentscope.core.model.AnthropicChatModel;

// Ollama (Local models)
import io.agentscope.core.model.OllamaChatModel;
```

### Optional Extensions

```xml
<!-- Long-term memory with Mem0 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mem0</artifactId>
    <version>${agentscope.version}</version>
</dependency>

<!-- RAG with Dify -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-dify</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

---

## PROJECT OVERVIEW & ARCHITECTURE

AgentScope Java is a reactive, message-driven multi-agent framework built on **Project Reactor** and **Java 17+**.

### Core Abstractions
- **`Agent`**: The fundamental unit of execution. Most agents extend `AgentBase`.
- **`Msg`**: The message object exchanged between agents.
- **`Memory`**: Stores conversation history (`InMemoryMemory`, `LongTermMemory`).
- **`Toolkit` & `AgentTool`**: Defines capabilities the agent can use.
- **`Model`**: Interfaces with LLMs (OpenAI, DashScope, Gemini, Anthropic, etc.).
- **`Hook`**: Intercepts and modifies agent execution at various lifecycle points.
- **`Pipeline`**: Orchestrates multiple agents in sequential or parallel patterns.

### Reactive Nature
Almost all operations (agent calls, model inference, tool execution) return `Mono<T>` or `Flux<T>`.

### Key Design Principles
1. **Non-blocking**: All I/O operations are asynchronous
2. **Message-driven**: Agents communicate via immutable `Msg` objects
3. **Composable**: Agents and pipelines can be nested and combined
4. **Extensible**: Hooks and custom tools allow deep customization

---

## CODING STANDARDS & BEST PRACTICES

### 2.1 Java Version & Style

**Target Java 17 (LTS) for maximum compatibility:**
- Use **Java 17** features (Records, Switch expressions, Pattern Matching for instanceof, `var`, Sealed classes)
- **AVOID Java 21+ preview features** (pattern matching in switch, record patterns)
- Follow standard Java conventions (PascalCase for classes, camelCase for methods/variables)
- Use **Lombok** where appropriate (`@Data`, `@Builder` for DTOs/Messages)
- Prefer **immutability** for data classes
- Use **meaningful names** that reflect domain concepts

**⚠️ CRITICAL: Avoid Preview Features**
```java
// ❌ WRONG - Requires Java 21 with --enable-preview
return switch (event) {
    case PreReasoningEvent e -> Mono.just(e);  // Pattern matching in switch
    default -> Mono.just(event);
};

// ✅ CORRECT - Java 17 compatible
if (event instanceof PreReasoningEvent e) {  // Pattern matching for instanceof (Java 17)
    return Mono.just(event);
} else {
    return Mono.just(event);
}
```

### 2.2 Reactive Programming (Critical)

**⚠️ NEVER BLOCK IN AGENT LOGIC**

Blocking operations will break the reactive chain and cause performance issues.

**Rules:**
- ❌ **Never use `.block()`** in agent logic (only in `main` methods or tests)
- ✅ Use `Mono` for single results (e.g., `agent.call()`)
- ✅ Use `Flux` for streaming responses (e.g., `model.stream()`)
- ✅ Chain operations using `.map()`, `.flatMap()`, `.then()`
- ✅ Use `Mono.defer()` for lazy evaluation
- ✅ Use `Mono.deferContextual()` for reactive context access

**Example:**
```java
// ❌ WRONG - Blocking
public Mono<String> processData(String input) {
    String result = externalService.call(input).block(); // DON'T DO THIS
    return Mono.just(result);
}

// ✅ CORRECT - Non-blocking
public Mono<String> processData(String input) {
    return externalService.call(input)
        .map(this::transform)
        .flatMap(this::validate);
}
```

### 2.3 Message Handling (`Msg`)

Create messages using the Builder pattern:
```java
Msg userMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder().text("Hello").build())
    .name("user")
    .build();
```

**Content Blocks:**
- **`TextBlock`**: For text content
- **`ThinkingBlock`**: For Chain of Thought (CoT) reasoning
- **`ToolUseBlock`**: For tool calls
- **`ToolResultBlock`**: For tool outputs

**Helper Methods:**
```java
// Prefer safe helper methods
String text = msg.getTextContent();  // Safe, returns null if not found

// Avoid direct access
String text = msg.getContent().get(0).getText();  // May throw NPE
```

### 2.4 Implementing Agents

Extend `AgentBase` and implement `doCall(List<Msg> msgs)`:

```java
public class MyAgent extends AgentBase {
    private final Model model;
    private final Memory memory;
    
    public MyAgent(String name, Model model) {
        super(name, "A custom agent", true, List.of());
        this.model = model;
        this.memory = new InMemoryMemory();
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        // 1. Process inputs
        if (msgs != null) {
            msgs.forEach(memory::addMessage);
        }
        
        // 2. Call model or logic
        return model.generate(memory.getMessages(), null, null)
            .map(response -> Msg.builder()
                .name(getName())
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(response.getText()).build())
                .build());
    }
}
```

### 2.5 Tool Definition

Use `@Tool` annotation for function-based tools. Tools can return:
- **`String`** (synchronous)
- **`Mono<String>`** (asynchronous)
- **`Mono<ToolResultBlock>`** (for complex results)

**⚠️ CRITICAL: @ToolParam Format**
- ✅ CORRECT: `@ToolParam(name = "city", description = "City name")`
- ❌ WRONG: `@ToolParam(name="city", description="...")` (no spaces around `=`)
- ❌ WRONG: `@ToolParam("city")` (missing name= and description=)

**Synchronous Tool Example:**
```java
public class WeatherTools {
    @Tool(description = "Get current weather for a city. Returns temperature and conditions.")
    public String getWeather(
            @ToolParam(name = "city", description = "City name, e.g., 'San Francisco'") 
            String city) {
        // Implementation
        return "Sunny, 25°C";
    }
}
```

**Asynchronous Tool Example:**
```java
public class AsyncTools {
    private final WebClient webClient;
    
    @Tool(description = "Fetch data from trusted API endpoint")
    public Mono<String> fetchData(
            @ToolParam(name = "url", description = "API endpoint URL (must start with https://api.myservice.com)") 
            String url) {
        // SECURITY: Validate URL to prevent SSRF
        if (!url.startsWith("https://api.myservice.com")) {
            return Mono.just("Error: URL not allowed. Must start with https://api.myservice.com");
        }

        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(10))
            .onErrorResume(e -> Mono.just("Error: " + e.getMessage()));
    }
}
```

**Register with Toolkit:**
```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherTools());
toolkit.registerTool(new AsyncTools());
```

---

## HOOK SYSTEM

Hooks allow you to intercept and modify agent execution at various lifecycle points.

### Hook Interface
```java
public interface Hook {
    <T extends HookEvent> Mono<T> onEvent(T event);
    default int priority() { return 100; }  // Lower = higher priority
}
```

### Common Hook Events
- **`PreReasoningEvent`**: Before LLM reasoning (modifiable)
- **`PostReasoningEvent`**: After LLM reasoning (modifiable)
- **`ReasoningChunkEvent`**: Streaming reasoning chunks (notification)
- **`PreActingEvent`**: Before tool execution (modifiable)
- **`PostActingEvent`**: After tool execution (modifiable)
- **`ActingChunkEvent`**: Streaming tool execution (notification)

### Hook Example

**Java 17+ compatible (recommended):**
```java
Hook loggingHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // Use if-instanceof instead of switch patterns (Java 17 compatible)
        if (event instanceof PreReasoningEvent e) {
            log.info("Reasoning with model: {}", e.getModelName());
            return Mono.just(event);
        } else if (event instanceof PreActingEvent e) {
            log.info("Calling tool: {}", e.getToolUse().getName());
            return Mono.just(event);
        } else if (event instanceof PostActingEvent e) {
            log.info("Tool {} completed", e.getToolUse().getName());
            return Mono.just(event);
        } else {
            return Mono.just(event);
        }
    }
    
    @Override
    public int priority() {
        return 500;  // Low priority (logging)
    }
};

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .hook(loggingHook)
    .build();
```

**Alternative: Traditional if-else (Java 17):**
```java
Hook loggingHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent) {
            PreReasoningEvent e = (PreReasoningEvent) event;
            log.info("Reasoning with model: {}", e.getModelName());
        } else if (event instanceof PreActingEvent) {
            PreActingEvent e = (PreActingEvent) event;
            log.info("Calling tool: {}", e.getToolUse().getName());
        } else if (event instanceof PostActingEvent) {
            PostActingEvent e = (PostActingEvent) event;
            log.info("Tool {} completed", e.getToolUse().getName());
        }
        return Mono.just(event);
    }
    
    @Override
    public int priority() {
        return 500;
    }
};
```

**Priority Guidelines:**
- **0-50**: Critical system hooks (auth, security)
- **51-100**: High priority hooks (validation, preprocessing)
- **101-500**: Normal priority hooks (business logic)
- **501-1000**: Low priority hooks (logging, metrics)

---

## PIPELINE PATTERNS

Pipelines orchestrate multiple agents in structured workflows.

### Sequential Pipeline
Executes agents in sequence (output of one becomes input of next):

```java
SequentialPipeline pipeline = SequentialPipeline.builder()
    .addAgent(researchAgent)
    .addAgent(summaryAgent)
    .addAgent(reviewAgent)
    .build();

Msg result = pipeline.execute(userInput).block();
```

### Fanout Pipeline
Executes agents in parallel and aggregates results:

```java
FanoutPipeline pipeline = FanoutPipeline.builder()
    .addAgent(agent1)
    .addAgent(agent2)
    .addAgent(agent3)
    .build();

Msg result = pipeline.execute(userInput).block();
```

**When to Use:**
- **Sequential**: When each agent depends on the previous agent's output
- **Fanout**: When agents can work independently and results need aggregation

---

## MEMORY MANAGEMENT

### In-Memory (Short-term)
```java
Memory memory = new InMemoryMemory();
```

### Long-Term Memory
```java
// Configure long-term memory
LongTermMemory longTermMemory = Mem0LongTermMemory.builder()
    .apiKey(System.getenv("MEM0_API_KEY"))
    .userId("user_123")
    .build();

// Use with agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .longTermMemory(longTermMemory)
    .longTermMemoryMode(LongTermMemoryMode.BOTH)  // STATIC_CONTROL, AGENTIC, or BOTH
    .build();
```

**Memory Modes:**
- **`STATIC_CONTROL`**: Framework automatically manages memory (via hooks)
- **`AGENTIC`**: Agent decides when to use memory (via tools)
- **`BOTH`**: Combines both approaches

---

## MCP (MODEL CONTEXT PROTOCOL) INTEGRATION

AgentScope supports MCP for integrating external tools and resources.

```java
// Create MCP client
// SECURITY: In production, use a specific version or a local binary to prevent supply chain attacks
McpClientWrapper mcpClient = McpClientBuilder.stdio()
    .command("npx")
    .args("-y", "@modelcontextprotocol/server-filesystem@0.6.2", "/path/to/files") // Always pin versions
    .build();

// Register with toolkit
Toolkit toolkit = new Toolkit();
toolkit.registration()
    .mcpClient(mcpClient)
    .enableTools(List.of("read_file", "write_file"))
    .apply();

// Use with agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .build();
```

---

## TESTING

### Unit Testing with StepVerifier
```java
@Test
void testAgentCall() {
    Msg input = Msg.builder()
        .role(MsgRole.USER)
        .content(TextBlock.builder().text("Hello").build())
        .build();
    
    StepVerifier.create(agent.call(input))
        .assertNext(response -> {
            assertEquals(MsgRole.ASSISTANT, response.getRole());
            assertNotNull(response.getTextContent());
        })
        .verifyComplete();
}
```

### Mocking External Dependencies
```java
@Test
void testWithMockModel() {
    Model mockModel = mock(Model.class);
    when(mockModel.generate(any(), any(), any()))
        .thenReturn(Mono.just(ChatResponse.builder()
            .text("Mocked response")
            .build()));
    
    ReActAgent agent = ReActAgent.builder()
        .name("TestAgent")
        .model(mockModel)
        .build();
    
    // Test agent behavior
}
```

**Testing Best Practices:**
- Always test reactive chains with `StepVerifier`
- Mock external dependencies (models, APIs)
- Test error cases and edge conditions
- Verify that hooks are called correctly
- Test timeout and cancellation scenarios

---

## CODE STYLE GUIDE

### Logging
```java
private static final Logger log = LoggerFactory.getLogger(MyClass.class);

// Use parameterized logging
log.info("Processing message from user: {}", userId);
log.error("Failed to call model: {}", modelName, exception);
```

### Error Handling
```java
// Prefer specific error messages
return Mono.error(new IllegalArgumentException(
    "Invalid model name: " + modelName + ". Expected one of: " + VALID_MODELS));

// Use onErrorResume for graceful degradation
return model.generate(msgs, null, null)
    .onErrorResume(e -> {
        log.error("Model call failed, using fallback", e);
        return Mono.just(fallbackResponse);
    });
```

### Null Safety
```java
// Use Optional for nullable returns
public Optional<AgentTool> findTool(String name) {
    return Optional.ofNullable(tools.get(name));
}

// Use Objects.requireNonNull for validation
public MyAgent(Model model) {
    this.model = Objects.requireNonNull(model, "Model cannot be null");
}
```

### Comments
```java
// Use Javadoc for public APIs
/**
 * Creates a new agent with the specified configuration.
 *
 * @param name The agent name (must be unique)
 * @param model The LLM model to use
 * @return Configured agent instance
 * @throws IllegalArgumentException if name is null or empty
 */
public static ReActAgent create(String name, Model model) {
    // Implementation
}

// Use inline comments sparingly, only for complex logic
// Calculate exponential backoff: 2^attempt * baseDelay
Duration delay = Duration.ofMillis((long) Math.pow(2, attempt) * baseDelayMs);
```

---

## KEY LIBRARIES

- **Reactor Core**: `Mono`, `Flux` for reactive programming
- **Jackson**: JSON serialization/deserialization
- **SLF4J**: Logging (`private static final Logger log = LoggerFactory.getLogger(MyClass.class);`)
- **OkHttp**: HTTP client for model APIs
- **MCP SDK**: Model Context Protocol integration
- **JUnit 5**: Testing framework
- **Mockito**: Mocking framework
- **Lombok**: Boilerplate reduction

---

## PROHIBITED PRACTICES

### ❌ NEVER Do These

1. **Block in reactive chains**
   ```java
   // ❌ WRONG
   return someMonoOperation().block();
   ```

2. **Use Thread.sleep() or blocking I/O**
   ```java
   // ❌ WRONG
   Thread.sleep(1000);
   
   // ✅ CORRECT
   return Mono.delay(Duration.ofSeconds(1));
   ```

3. **Mutate shared state without synchronization**
   ```java
   // ❌ WRONG
   private List<Msg> messages = new ArrayList<>();
   public void addMessage(Msg msg) {
       messages.add(msg);  // Not thread-safe
   }
   ```

4. **Ignore errors silently**
   ```java
   // ❌ WRONG
   .onErrorResume(e -> Mono.empty())
   
   // ✅ CORRECT
   .onErrorResume(e -> {
       log.error("Operation failed", e);
       return Mono.just(fallbackValue);
   })
   ```

5. **Use ThreadLocal in reactive code**
   ```java
   // ❌ WRONG
   ThreadLocal<String> context = new ThreadLocal<>();
   
   // ✅ CORRECT
   return Mono.deferContextual(ctx -> {
       String value = ctx.get("key");
       // Use value
   });
   ```

6. **Create agents without proper resource management**
   ```java
   // ❌ WRONG - No cleanup
   public void processRequests() {
       for (int i = 0; i < 1000; i++) {
           ReActAgent agent = createAgent();
           agent.call(msg).block();
       }
   }
   ```

7. **Hardcode API keys or secrets**
   ```java
   // ❌ WRONG
   String apiKey = "sk-1234567890";
   
   // ✅ CORRECT
   String apiKey = System.getenv("OPENAI_API_KEY");
   ```

8. **Use Java preview features (requires --enable-preview)**
   ```java
   // ❌ WRONG - Requires Java 21 with --enable-preview
   return switch (event) {
       case PreReasoningEvent e -> handleReasoning(e);
       case PostActingEvent e -> handleActing(e);
       default -> Mono.just(event);
   };
   
   // ✅ CORRECT - Java 17 compatible
   if (event instanceof PreReasoningEvent e) {
       return handleReasoning(e);
   } else if (event instanceof PostActingEvent e) {
       return handleActing(e);
   } else {
       return Mono.just(event);
   }
   ```

---

## COMMON PITFALLS & SOLUTIONS

### ❌ Blocking Operations
```java
// WRONG
Msg response = agent.call(msg).block();  // Don't block in agent logic
```

```java
// CORRECT
return agent.call(msg)
    .flatMap(response -> processResponse(response));
```

### ❌ Null Handling
```java
// WRONG
String text = msg.getContent().get(0).getText();  // May throw NPE
```

```java
// CORRECT
String text = msg.getTextContent();  // Safe helper method
// OR
String text = msg.getContentBlocks(TextBlock.class).stream()
    .findFirst()
    .map(TextBlock::getText)
    .orElse("");
```

### ❌ Thread Context
```java
// WRONG
ThreadLocal<String> context = new ThreadLocal<>();  // May not work in reactive streams
```

```java
// CORRECT
return Mono.deferContextual(ctx -> {
    String value = ctx.get("key");
    // Use value
});
```

### ❌ Error Swallowing
```java
// WRONG
.onErrorResume(e -> Mono.empty())  // Silent failure
```

```java
// CORRECT
.onErrorResume(e -> {
    log.error("Failed to process: {}", input, e);
    return Mono.just(createErrorResponse(e));
})
```

---

## COMPLETE EXAMPLE

```java
package com.example.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.model.DashScopeChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Complete example demonstrating AgentScope best practices.
 */
public class CompleteExample {
    
    private static final Logger log = LoggerFactory.getLogger(CompleteExample.class);
    
    public static void main(String[] args) {
        // 1. Create model (no .temperature() method, use defaultOptions)
        Model model = DashScopeChatModel.builder()
            .apiKey(System.getenv("DASHSCOPE_API_KEY"))
            .modelName("qwen-plus")
            .stream(true)
            .build();
        
        // 2. Create toolkit with tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());
        toolkit.registerTool(new TimeTools());
        
        // 3. Create hook for streaming output
        Hook streamingHook = new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof ReasoningChunkEvent e) {
                    String text = e.getIncrementalChunk().getTextContent();
                    if (text != null) {
                        System.out.print(text);
                    }
                }
                return Mono.just(event);
            }
            
            @Override
            public int priority() {
                return 500;  // Low priority
            }
        };
        
        // 4. Build agent
        ReActAgent agent = ReActAgent.builder()
            .name("Assistant")
            .sysPrompt("You are a helpful assistant. Use tools when appropriate.")
            .model(model)
            .toolkit(toolkit)
            .memory(new InMemoryMemory())
            .hook(streamingHook)
            .maxIters(10)
            .build();
        
        // 5. Use agent
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder()
                .text("What's the weather in San Francisco and what time is it?")
                .build())
            .build();
        
        try {
            System.out.println("User: " + userMsg.getTextContent());
            System.out.print("Assistant: ");
            
            // ⚠️ IMPORTANT: .block() is ONLY allowed in main() methods for demo purposes
            // NEVER use .block() in agent logic, service methods, or library code
            Msg response = agent.call(userMsg).block();
            
            System.out.println("\n\n--- Response Details ---");
            System.out.println("Role: " + response.getRole());
            System.out.println("Content: " + response.getTextContent());
            
        } catch (Exception e) {
            log.error("Error during agent execution", e);
            System.err.println("Error: " + e.getMessage());
        }
    }
    
    /**
     * Example tool class for weather information.
     */
    public static class WeatherTools {
        
        @Tool(description = "Get current weather for a city. Returns temperature and conditions.")
        public String getWeather(
                @ToolParam(name = "city", description = "City name, e.g., 'San Francisco'") 
                String city) {
            
            log.info("Getting weather for city: {}", city);
            
            // Simulate API call
            return String.format("Weather in %s: Sunny, 22°C, Light breeze", city);
        }
    }
    
    /**
     * Example tool class for time information.
     */
    public static class TimeTools {
        
        private static final DateTimeFormatter FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        @Tool(description = "Get current date and time")
        public String getCurrentTime() {
            LocalDateTime now = LocalDateTime.now();
            String formatted = now.format(FORMATTER);
            
            log.info("Returning current time: {}", formatted);
            
            return "Current time: " + formatted;
        }
    }
}
```

---

## QUICK REFERENCE

### Agent Creation
```java
ReActAgent agent = ReActAgent.builder()
    .name("AgentName")
    .sysPrompt("System prompt")
    .model(model)
    .toolkit(toolkit)
    .memory(memory)
    .hooks(hooks)
    .maxIters(10)
    .build();
```

### Message Creation
```java
Msg msg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder().text("Hello").build())
    .build();
```

### Tool Registration
```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new MyTools());
```

### Hook Creation
```java
Hook hook = new Hook() {
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // Handle event
        return Mono.just(event);
    }
};
```

### Pipeline Creation
```java
SequentialPipeline pipeline = SequentialPipeline.builder()
    .addAgent(agent1)
    .addAgent(agent2)
    .build();
```

---
