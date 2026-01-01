# AgentScope Java - LLM Guide for AI Code Assistants

## SYSTEM MESSAGE FOR AI ASSISTANTS

You are an AI assistant helping developers use the AgentScope Java framework.
AgentScope Java is an agent-oriented programming framework for building LLM-powered applications in Java.
This guide provides core concepts, APIs, patterns, and best practices.

When generating code:
- Follow the Builder pattern for all major components
- Use reactive programming with Mono/Flux from Project Reactor
- Handle errors gracefully with proper exception handling
- Follow Java naming conventions and code style
- Include necessary imports

---

## QUICK START

### Minimal Working Example

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.message.Msg;

// Create agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful assistant.")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .build())
    .build();

// Call agent
Msg response = agent.call(Msg.builder()
    .textContent("Hello!")
    .build()).block();
    
System.out.println(response.getTextContent());
```

### Maven Dependencies

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.5</version>
</dependency>
```

Requirements: JDK 17+

---

## CORE CONCEPTS

### 1. Message (Msg)

Messages are the fundamental data structure for agent communication.

**Structure:**
- name: Sender's name
- role: USER, ASSISTANT, SYSTEM, or TOOL
- content: List of content blocks (TextBlock, ImageBlock, ToolUseBlock, etc.)
- metadata: Optional structured data

**Creating Messages:**

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;

// Simple text message
Msg msg = Msg.builder()
    .name("user")
    .role(MsgRole.USER)
    .textContent("What's the weather?")
    .build();

// Message with multiple content blocks
Msg multiMsg = Msg.builder()
    .name("user")
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("Describe this image").build(),
        ImageBlock.builder()
            .source(URLSource.builder()
                .url("https://example.com/image.jpg")
                .build())
            .build()
    ))
    .build();

// Getting content from message
String text = msg.getTextContent();
List<ContentBlock> blocks = msg.getContent();
```

### 2. ReActAgent

The main agent class implementing the ReAct (Reasoning + Acting) loop.

**Key Components:**
- Model: LLM for reasoning
- Toolkit: Available tools
- Memory: Conversation history
- Hooks: Event-driven customization

**Builder Pattern:**

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.tool.Toolkit;

ReActAgent agent = ReActAgent.builder()
    // Required
    .name("Assistant")                    // Agent name
    .model(model)                         // LLM model
    
    // Optional
    .sysPrompt("You are helpful")         // System prompt
    .memory(new InMemoryMemory())         // Memory (default: InMemoryMemory)
    .toolkit(toolkit)                     // Tools (default: empty)
    .maxIters(10)                         // Max reasoning loops (default: 10)
    .enablePlan()                         // Enable planning
    .enableMetaTool(true)                 // Enable tool group management
    
    // Long-term memory
    .longTermMemory(longTermMemory)
    .longTermMemoryMode(LongTermMemoryMode.BOTH)
    
    // RAG
    .knowledge(knowledge)                 // Add single knowledge base
    // OR .knowledges(List.of(k1, k2))    // Add multiple knowledge bases
    .ragMode(RAGMode.AGENTIC)
    
    // Hooks
    .hooks(List.of(customHook))
    
    .build();
```

**Usage Patterns:**

```java
// Synchronous call
Msg response = agent.call(userMsg).block();

// Async call
agent.call(userMsg)
    .subscribe(response -> {
        System.out.println(response.getTextContent());
    });

// Streaming execution events
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;

agent.stream(userMsg, StreamOptions.defaults())
    .subscribe(event -> {
        if (event.getType() == EventType.REASONING) {
            if (event.isLast()) {
                System.out.println("Reasoning complete");
            } else {
                System.out.print(event.getMessage().getTextContent());
            }
        }
    });
```

### 3. Model Integration

**DashScope (Alibaba Cloud):**

```java
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.GenerateOptions;

DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-max")              // qwen3-max, qwen3-max, qwen-turbo
    .stream(true)                        // Enable streaming
    .enableThinking(true)                // Enable reasoning (for reasoning models)
    .formatter(new DashScopeChatFormatter())
    .defaultOptions(GenerateOptions.builder()
        .temperature(0.7)
        .maxTokens(2000)
        .topP(0.9)
        .thinkingBudget(1024)            // For reasoning models
        .build())
    .build();
```

**OpenAI:**

```java
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.formatter.openai.OpenAIFormatter;

OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")                 // gpt-4o, gpt-4-turbo, o1
    .stream(true)
    .formatter(new OpenAIFormatter())
    .defaultOptions(GenerateOptions.builder()
        .temperature(0.7)
        .maxTokens(2000)
        .build())
    .build();
```

**OpenAI-Compatible APIs (DeepSeek, vLLM, etc.):**

```java
OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey("your-api-key")
    .modelName("deepseek-chat")
    .baseUrl("https://api.deepseek.com")  // Custom endpoint
    .build();
```

### 4. Tool System

Tools extend agent capabilities with custom functions.

**Defining Tools with @Tool annotation:**

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class WeatherService {
    @Tool(
        description = "Get current weather for a city"
    )
    public String getWeather(
        @ToolParam(description = "City name") String city
    ) {
        // Implementation
        return "Weather in " + city + ": Sunny, 25°C";
    }
    
    @Tool(description = "Calculate sum of two numbers")
    public int add(
        @ToolParam(description = "First number") int a,
        @ToolParam(description = "Second number") int b
    ) {
        return a + b;
    }
}
```

**Registering Tools:**

```java
import io.agentscope.core.tool.Toolkit;

Toolkit toolkit = new Toolkit();

// Register instance (all @Tool methods)
toolkit.registerTool(new WeatherService());

// Register another tool instance
toolkit.registerTool(serviceInstance);
```

**Tool with Context:**

```java
import io.agentscope.core.tool.ToolExecutionContext;

public class EmailService {
    @Tool(description = "Send email")
    public String sendEmail(
        @ToolParam(description = "To address") String to,
        @ToolParam(description = "Subject") String subject,
        @ToolParam(description = "Body") String body,
        ToolExecutionContext context  // Injected automatically
    ) {
        // Access context values by key and type
        String apiKey = context.get("apiKey", String.class);
        // Or inject custom objects by type (if registered in context)
        // UserContext userCtx = context.get(UserContext.class);
        return "Email sent with key: " + apiKey;
    }
}

// Note: To inject custom objects, register them in the context:
// context.register(userContextInstance);

// Configure context - register values or objects
ToolExecutionContext context = ToolExecutionContext.builder()
    .register("apiKey", "secret-key")
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Agent")
    .toolkit(toolkit)
    .toolExecutionContext(context)
    .build();
```

### 5. Memory Management

**Short-term Memory (InMemoryMemory):**

```java
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;

Memory memory = new InMemoryMemory();

// Memory is managed automatically by agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .memory(memory)
    .build();

// Access memory
List<Msg> history = agent.getMemory().getMessages();
```

**Long-term Memory (Mem0):**

```java
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;

Mem0LongTermMemory longTermMemory = Mem0LongTermMemory.builder()
    .agentName("Assistant")
    .userId("user123")
    .apiKey(System.getenv("MEM0_API_KEY"))
    .apiBaseUrl("https://api.mem0.ai")
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .longTermMemory(longTermMemory)
    .longTermMemoryMode(LongTermMemoryMode.BOTH)  // STATIC_CONTROL, AGENT_CONTROL, or BOTH
    .build();
```

**Long-term Memory (ReMe):**

```java
import io.agentscope.core.memory.reme.ReMeLongTermMemory;

ReMeLongTermMemory longTermMemory = ReMeLongTermMemory.builder()
    .userId("user123")
    .apiBaseUrl("http://localhost:8002")
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .longTermMemory(longTermMemory)
    .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
    .build();
```

**AutoContextMemory (Automatic Context Compression):**

```java
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.AutoContextConfig;

AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(30)        // Compress when messages exceed this
    .lastKeep(10)            // Keep last N messages
    .tokenRatio(0.3)         // Alternative: compress at token ratio
    .build();

AutoContextMemory memory = new AutoContextMemory(config, model);

ReActAgent agent = ReActAgent.builder()
    .memory(memory)
    .build();
```

### 6. RAG (Retrieval-Augmented Generation)

**Local Knowledge Base:**

```java
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.RAGMode;

// Create knowledge base
SimpleKnowledge knowledge = SimpleKnowledge.builder()
    .embeddingModel(embeddingModel)
    .embeddingStore(new InMemoryStore())
    .build();

// Load documents (you need to create Document objects)
knowledge.addDocuments(documents).block();

// Use in agent (Generic Mode - automatic retrieval)
ReActAgent agent = ReActAgent.builder()
    .name("RAGAgent")
    .model(model)
    .knowledge(knowledge)
    .ragMode(RAGMode.GENERIC)
    .build();

// Or Agentic Mode - agent controls retrieval
ReActAgent agenticAgent = ReActAgent.builder()
    .name("RAGAgent")
    .model(model)
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)
    .build();
```

**Cloud Knowledge Base (Bailian):**

```java
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.integration.bailian.BailianConfig;

BailianConfig config = BailianConfig.builder()
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxx")
    .indexId("mymxbdxxxx")
    .build();

BailianKnowledge knowledge = BailianKnowledge.builder()
    .config(config)
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("KnowledgeAssistant")
    .model(model)
    .knowledge(BailianKnowledge.builder()
        .config(config)
        .build())
    .ragMode(RAGMode.AGENTIC)
    .build();
```

### 7. Structured Output

Force agent to return data in specific format.

```java
import io.agentscope.core.model.StructuredOutputReminder;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

// Define schema
public class ProductRequirements {
    @JsonProperty(required = true)
    @JsonPropertyDescription("Product type")
    public String productType;
    
    @JsonProperty(required = true)
    @JsonPropertyDescription("Brand name")
    public String brand;
    
    @JsonProperty
    @JsonPropertyDescription("Budget in USD")
    public Integer budget;
}

// Use structured output
ReActAgent agent = ReActAgent.builder()
    .name("Analyzer")
    .model(model)
    .structuredOutputReminder(StructuredOutputReminder.TOOL_CHOICE)  // TOOL_CHOICE or PROMPT
    .build();

// Call with schema
Msg response = agent.call(userMsg, ProductRequirements.class).block();
ProductRequirements data = response.getStructuredData(ProductRequirements.class);
```

### 8. Hook System

Intercept and customize agent behavior with unified event model.

```java
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import reactor.core.publisher.Mono;

public class LoggingHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreReasoningEvent e -> {
                System.out.println("Reasoning with model: " + e.getModelName());
                yield Mono.just(e);
            }
            case PostReasoningEvent e -> {
                System.out.println("Model response: " + e.getReasoningResponse());
                yield Mono.just(e);
            }
            case PreActingEvent e -> {
                System.out.println("Executing tool: " + e.getToolUse().getName());
                yield Mono.just(e);
            }
            case PostActingEvent e -> {
                System.out.println("Tool result: " + e.getToolResult());
                yield Mono.just(e);
            }
            case ReasoningChunkEvent e -> {
                // Display streaming output
                System.out.print(e.getIncrementalChunk().getText());
                yield Mono.just(e);
            }
            default -> Mono.just(event);
        };
    }
}

// Modify events with high priority hook
public class HintInjectorHook implements Hook {
    @Override
    public int priority() {
        return 10;  // High priority (default is 100)
    }
    
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreReasoningEvent e -> {
                // Inject hint before LLM reasoning
                List<Msg> msgs = new ArrayList<>(e.getInputMessages());
                msgs.add(0, Msg.builder()
                    .role(MsgRole.SYSTEM)
                    .textContent("Think step by step")
                    .build());
                e.setInputMessages(msgs);
                yield Mono.just(e);
            }
            default -> Mono.just(event);
        };
    }
}

// Register hooks
ReActAgent agent = ReActAgent.builder()
    .name("Agent")
    .hooks(List.of(new LoggingHook(), new HintInjectorHook()))
    .build();
```

### 9. Planning

Enable agents to break down complex tasks.

```java
ReActAgent agent = ReActAgent.builder()
    .name("PlanAgent")
    .model(model)
    .enablePlan()   // Automatically registers PlanNotebook tools
    .build();

// Agent can now use planning tools:
// - create_plan: Create a new plan
// - update_plan: Update existing plan
// - finish_plan: Mark plan as complete
```

### 10. Multi-Agent Pipelines

**Sequential Pipeline:**

```java
import io.agentscope.core.pipeline.SequentialPipeline;

SequentialPipeline pipeline = SequentialPipeline.builder()
    .addAgents(List.of(writer, reviewer, editor))
    .build();

Msg result = pipeline.execute(initialMsg).block();
```

**Fanout Pipeline (Parallel Execution):**

```java
import io.agentscope.core.pipeline.FanoutPipeline;

FanoutPipeline pipeline = new FanoutPipeline(
    List.of(expertA, expertB, expertC),
    true  // Enable concurrent execution
);

List<Msg> results = pipeline.execute(taskMsg).block();
```

**MsgHub (Multi-agent Conversation):**

```java
import io.agentscope.core.pipeline.MsgHub;

MsgHub hub = MsgHub.builder()
    .name("Discussion")
    .participants(List.of(alice, bob, charlie))
    .build();

// Broadcast message to all participants
hub.broadcast(moderatorMsg);
```

---

## COMMON PATTERNS

### Pattern 1: Basic Chat Agent

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful AI assistant.")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .stream(true)
        .build())
    .memory(new InMemoryMemory())
    .build();

// Interactive chat loop
BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
Msg msg = null;
while (true) {
    System.out.print("You: ");
    String input = reader.readLine();
    if (input.equals("exit")) break;
    
    msg = agent.call(Msg.builder().textContent(input).build()).block();
    System.out.println("Agent: " + msg.getTextContent());
}
```

### Pattern 2: Agent with Tools

```java
// Define tools
public class Tools {
    @Tool(description = "Search the web")
    public String search(@ToolParam(description = "Query") String query) {
        return "Search results for: " + query;
    }
    
    @Tool(description = "Calculate math expression")
    public double calculate(@ToolParam(description = "Expression") String expr) {
        // Implementation: parse and calculate expression
        // For example, using a math expression parser
        return 0.0; // Replace with actual calculation logic
    }
}

// Create agent
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new Tools());

ReActAgent agent = ReActAgent.builder()
    .name("ToolAgent")
    .model(model)
    .toolkit(toolkit)
    .sysPrompt("You are a helpful assistant. Use tools when needed.")
    .build();
```

### Pattern 3: Streaming Response

```java
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;

agent.stream(userMsg, StreamOptions.defaults())
    .subscribe(event -> {
        switch (event.getType()) {
            case REASONING -> {
                // Agent reasoning/thinking output
                if (!event.isLast()) {
                    // Streaming chunk - display incrementally
                    System.out.print(event.getMessage().getTextContent());
                }
            }
            case TOOL_RESULT -> {
                // Tool execution result
                System.out.println("\n[Tool] " + event.getMessage().getTextContent());
            }
            case HINT -> {
                // RAG/Memory/Planning hints
                System.out.println("[Hint] " + event.getMessage().getTextContent());
            }
        }
    });
```

### Pattern 4: Multi-modal Input

```java
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.Base64Source;

Msg visionMsg = Msg.builder()
    .name("user")
    .content(List.of(
        TextBlock.builder().text("What's in this image?").build(),
        ImageBlock.builder()
            .source(URLSource.builder()
                .url("https://example.com/image.jpg")
                .build())
            .build()
    ))
    .build();

// Or use Base64
Msg base64Msg = Msg.builder()
    .name("user")
    .content(List.of(
        TextBlock.builder().text("Analyze this").build(),
        ImageBlock.builder()
            .source(Base64Source.builder()
                .data("data:image/jpeg;base64,/9j/4AAQSkZJRg...")
                .build())
            .build()
    ))
    .build();

// Use with vision model (qwen-vl-max, gpt-4o, etc.)
ReActAgent visionAgent = ReActAgent.builder()
    .name("VisionAgent")
    .model(DashScopeChatModel.builder()
        .apiKey(apiKey)
        .modelName("qwen-vl-max")
        .build())
    .build();

Msg response = visionAgent.call(visionMsg).block();
```

### Pattern 5: State Persistence

```java
import io.agentscope.core.session.SessionManager;
import io.agentscope.core.session.JsonSession;
import java.nio.file.Path;

// Create agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .build();

String sessionId = "user123-session";

// Save session
SessionManager.forSessionId(sessionId)
    .withSession(new JsonSession(Path.of("./sessions")))
    .addComponent(agent)
    .saveSession();

// Load session
SessionManager.forSessionId(sessionId)
    .withSession(new JsonSession(Path.of("./sessions")))
    .addComponent(agent)
    .loadIfExists();
```

### Pattern 6: MCP Integration

```java
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

// Create MCP client with StdIO transport
McpClientWrapper mcpClient = McpClientBuilder.create("filesystem")
    .stdioTransport("npx", new String[]{
        "-y", 
        "@modelcontextprotocol/server-filesystem", 
        "/path/to/directory"
    })
    .buildAsync()
    .block();

// Register MCP tools in toolkit
Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(mcpClient).block();

ReActAgent agent = ReActAgent.builder()
    .name("McpAgent")
    .model(model)
    .toolkit(toolkit)
    .build();

// Remember to close MCP client when done
mcpClient.close();
```

---

## BEST PRACTICES

### 1. Error Handling

Always handle potential errors in reactive chains:

```java
agent.call(msg)
    .doOnError(error -> {
        log.error("Agent call failed", error);
    })
    .onErrorReturn(Msg.builder()
        .textContent("Sorry, an error occurred")
        .build())
    .block();
```

### 2. Resource Management

Clean up resources properly:

```java
// For models with custom HTTP clients
model.close();

// For MCP clients
try (McpClientWrapper mcpClient = createMcpClient()) {
    toolkit.registerMcpClient(mcpClient).block();
    // Use toolkit with MCP tools
}
```

### 3. System Prompts

Write clear, specific system prompts:

```java
String sysPrompt = """
    You are a professional customer service agent.
    
    Guidelines:
    - Be polite and professional
    - Answer concisely
    - Use tools when needed to get accurate information
    - If unsure, ask for clarification
    
    Limitations:
    - Do not make up information
    - Do not access user's private data without permission
    """;
```

### 4. Tool Design

Make tools focused and well-documented:

```java
@Tool(
    description = "Get weather forecast for a specific city and date. " +
                  "Returns temperature, conditions, and humidity."
)
public WeatherForecast getWeather(
    @ToolParam(description = "City name, e.g., 'Beijing' or 'New York'") 
    String city,
    
    @ToolParam(description = "Date in YYYY-MM-DD format, e.g., '2024-03-15'") 
    String date
) {
    // Implementation
}
```

### 5. Memory Management

Choose appropriate memory based on use case:

- **InMemoryMemory**: Simple conversations, short sessions
- **AutoContextMemory**: Long conversations needing compression
- **Mem0/ReMe**: Cross-session persistence, user profiles

### 6. Model Selection

- **qwen3-max**: General purpose, good balance
- **qwen3-max**: Complex reasoning, best quality
- **qwen-turbo**: Fast responses, simple tasks
- **gpt-4o**: Multi-modal, complex tasks
- **o1**: Deep reasoning tasks

### 7. Testing

Write tests for agent behaviors:

```java
@Test
void testAgentWithTools() {
    Toolkit toolkit = new Toolkit();
    toolkit.registerTool(new MockWeatherService());
    
    ReActAgent agent = ReActAgent.builder()
        .name("TestAgent")
        .model(mockModel)
        .toolkit(toolkit)
        .build();
    
    Msg response = agent.call(
        Msg.builder().textContent("Weather in Beijing?").build()
    ).block();
    
    assertNotNull(response);
    assertTrue(response.getTextContent().contains("Beijing"));
}
```

---

## COMMON ISSUES AND SOLUTIONS

### Issue 1: Agent Not Using Tools

**Problem:** Agent responds directly instead of calling tools.

**Solutions:**
- Improve system prompt to emphasize tool usage
- Ensure tool descriptions are clear
- Use more capable models (qwen3-max, gpt-4o)
- Check if tools are properly registered in toolkit

```java
.sysPrompt("""
    You are an assistant with access to tools.
    ALWAYS use the appropriate tool when available to answer questions.
    Do not make up information - use tools to get accurate data.
    """)
```

### Issue 2: Out of Context Length

**Problem:** Conversation exceeds model's context window.

**Solutions:**
- Use AutoContextMemory for automatic compression
- Implement manual context windowing
- Summarize old conversations

```java
AutoContextConfig config = AutoContextConfig.builder()
    .tokenRatio(0.7)  // Compress at 70% of context
    .lastKeep(10)     // Keep last 10 messages
    .build();
    
AutoContextMemory memory = new AutoContextMemory(config, model);
```

### Issue 3: Slow Response Times

**Solutions:**
- Enable streaming for faster perceived response
- Use faster models (qwen-turbo)
- Implement caching for repeated queries
- Run tool executions in parallel if possible

```java
// Enable streaming
.model(DashScopeChatModel.builder()
    .modelName("qwen-turbo")  // Faster model
    .stream(true)             // Stream responses
    .build())
```

### Issue 4: Tool Execution Failures

**Solutions:**
- Add proper error handling in tools
- Validate tool parameters
- Use ToolExecutionContext for debugging
- Log tool execution details

```java
@Tool(description = "Safe division")
public Double divide(
    @ToolParam(description = "Numerator") double a,
    @ToolParam(description = "Denominator") double b
) {
    if (b == 0) {
        throw new IllegalArgumentException("Cannot divide by zero");
    }
    return a / b;
}
```

### Issue 5: Memory Not Persisting

**Problem:** Agent forgets context between sessions.

**Solution:** Use SessionManager to persist agent state:

```java
// Save session after conversation
SessionManager.forSessionId(sessionId)
    .withSession(new JsonSession(Path.of("./sessions")))
    .addComponent(agent)
    .saveSession();

// Load session before next conversation
SessionManager.forSessionId(sessionId)
    .withSession(new JsonSession(Path.of("./sessions")))
    .addComponent(agent)
    .loadIfExists();
```

---

## ADVANCED TOPICS

### Custom Model Integration

```java
import io.agentscope.core.model.Model;

public class CustomModel implements Model {
    @Override
    public Mono<ChatResponse> call(List<Msg> messages, 
                                    GenerateOptions options,
                                    List<ToolDef> tools) {
        // Implement custom model integration
    }
}
```

### Custom Memory Implementation

```java
import io.agentscope.core.memory.Memory;

public class RedisMemory implements Memory {
    @Override
    public void addMessage(Msg message) {
        // Store in Redis
    }
    
    @Override
    public List<Msg> getMessages() {
        // Retrieve from Redis
    }
}
```

### Custom Tool Loader

```java
public class DynamicToolLoader {
    public void loadToolsFromPackage(Toolkit toolkit, String packageName) {
        // Scan package for @Tool annotations
        // Register discovered tools
    }
}
```

---

## API QUICK REFERENCE

### ReActAgent.Builder Methods

```java
.name(String)                          // Agent name (required)
.model(Model)                          // LLM model (required)
.sysPrompt(String)                     // System prompt
.memory(Memory)                        // Conversation memory
.toolkit(Toolkit)                      // Available tools
.maxIters(int)                         // Max reasoning loops
.enablePlan()                          // Enable planning
.enableMetaTool(boolean)               // Enable tool groups
.longTermMemory(LongTermMemory)        // Long-term memory
.longTermMemoryMode(Mode)              // Memory mode
.knowledge(Knowledge)                  // Add single RAG knowledge base
.knowledges(List<Knowledge>)           // Add multiple RAG knowledge bases
.ragMode(RAGMode)                      // RAG mode
.hooks(List<Hook>)                     // Event hooks
.toolExecutionContext(Context)         // Tool context
.structuredOutputReminder(Reminder)    // Structured output mode
```

### Msg.Builder Methods

```java
.name(String)                          // Sender name
.role(MsgRole)                         // Message role
.textContent(String)                   // Simple text content
.content(List<ContentBlock>)           // Multiple content blocks
.metadata(Map)                         // Additional data
```

### Model Builder Methods (DashScope/OpenAI)

```java
.apiKey(String)                        // API key
.modelName(String)                     // Model identifier
.baseUrl(String)                       // Custom endpoint
.stream(boolean)                       // Enable streaming
.enableThinking(boolean)               // Reasoning traces
.formatter(Formatter)                  // Message formatter
.defaultOptions(GenerateOptions)       // Default parameters
```

### Toolkit Methods

```java
.registerTool(Object)                  // Register object with @Tool methods
.registerAgentTool(AgentTool)          // Register AgentTool instance
.registerMcpClient(McpClientWrapper)   // Register MCP server tools
.getTools()                            // Get all registered tools
.executeTool(name, args)               // Execute tool by name
```

---

## EXAMPLES INDEX

1. **Basic Chat**: Minimal agent setup
2. **Tool Calling**: Agent using external tools
3. **Streaming**: Real-time response streaming
4. **Multi-modal**: Image/audio processing
5. **RAG**: Knowledge base integration
6. **Multi-agent**: Pipeline and MsgHub
7. **Memory**: Short and long-term memory
8. **Planning**: Complex task decomposition
9. **Structured Output**: Typed response parsing
10. **MCP Integration**: Model Context Protocol

---

## IMPORTANT REMINDERS

1. **Always use Builder pattern** - Never try to instantiate objects directly
2. **Handle Mono/Flux properly** - Use .block() carefully, prefer reactive chains
3. **Import correctly** - Use io.agentscope.core.* packages
4. **Resource cleanup** - Close connections and cleanup resources
5. **Error handling** - Always handle potential errors in reactive chains
6. **Testing** - Write tests for agent behaviors
7. **Documentation** - Add clear descriptions to tools and prompts

---

## GETTING HELP

- Documentation: https://java.agentscope.io/
- GitHub: https://github.com/agentscope-ai/agentscope-java
- Examples: agentscope-examples/ directory in repository
- Discord: https://discord.gg/eYMpfnkG8h

---

This guide is optimized for LLM code assistants. When generating AgentScope Java code:
- Follow these patterns and conventions
- Use the Builder pattern consistently
- Include proper imports
- Handle errors appropriately
- Add helpful comments
- Follow Java best practices
