# Task Tools - Hierarchical Sub-Agent System

## Overview

The Task Tools provide hierarchical autonomous sub-agent capabilities for Spring AI, inspired by [Claude Code's sub-agents](https://code.claude.com/docs/en/sub-agents). This system enables your main AI agent to delegate complex, multi-step tasks to specialized sub-agents that work autonomously with their own context, tools, and system prompts.

## Key Concepts

### What Are Sub-Agents?

Sub-agents are specialized AI assistants that your main agent can delegate tasks to. Each operates with:

- **Dedicated context window** - Separate from the main conversation, preventing context pollution
- **Custom system prompt** - Tailored instructions for specific domains (exploration, planning, custom expertise)
- **Configurable tool access** - Limited to only the necessary capabilities for their role
- **Independent execution** - Works autonomously and returns results to the parent agent

### Benefits

| Benefit | Details |
|---------|---------|
| **Context Preservation** | Keeps main conversation focused on high-level objectives while sub-agents handle details |
| **Specialized Expertise** | Fine-tuned for specific domains with higher success rates |
| **Reusability** | Define once, use across projects, share with teams |
| **Flexible Permissions** | Granular control over tool access per sub-agent |
| **Async Execution** | Run sub-agents in background for long-running tasks |

## Architecture

The Task Tools consist of three main components:

1. **TaskTool** - Launches and manages sub-agents
2. **TaskOutputTool** - Retrieves results from background sub-agents
3. **TaskToolCallbackProvider** - Convenience builder for configuring the complete system

## Built-in Sub-Agents

### General-Purpose Sub-Agent

A versatile agent for complex research and execution tasks:

```markdown
---
name: general-purpose
description: General-purpose agent for researching complex questions, searching for code, and executing multi-step tasks
---
```

**Capabilities:**
- Searching code and configurations across large codebases
- Analyzing multiple files to understand system architecture
- Performing multi-step research tasks
- Full read/write access to tools

**Use Cases:**
- "Search for all REST controllers and analyze their patterns"
- "Find and update all hardcoded configuration values"

### Explore Sub-Agent

A fast, read-only agent specialized for codebase exploration:

```markdown
---
name: Explore
description: Fast agent specialized for exploring codebases. Use for finding files, searching code, or answering questions about the codebase
---
```

**Capabilities:**
- Rapidly finding files using glob patterns
- Searching code with regex patterns
- Reading and analyzing file contents
- **Strictly read-only** - no file modifications

**Thoroughness Levels:**
- `quick` - Basic searches, minimal exploration
- `medium` - Moderate exploration, multiple search strategies
- `very thorough` - Comprehensive analysis across multiple locations

**Use Cases:**
- "How are REST endpoints structured in this codebase?"
- "Find all files that use Spring Security"
- "Explore the authentication implementation"

## Quick Start

### Basic Setup

```java
import org.springaicommunity.agent.tools.task.TaskToolCallbackProvider;
import org.springframework.ai.chat.client.ChatClient;

@Configuration
public class AgentConfig {

    @Bean
    CommandLineRunner demo(ChatClient.Builder chatClientBuilder) {
        return args -> {
            // Configure Task tools with sub-agents
            var taskTools = TaskToolCallbackProvider.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();

            // Build main chat client with Task tools
            ChatClient chatClient = chatClientBuilder
                .defaultToolCallbacks(taskTools)
                // ... add other tools ...
                .build();

            // Use naturally - agent will delegate to sub-agents
            String response = chatClient
                .prompt("Explore the authentication module and explain how it works")
                .call()
                .content();
        };
    }
}
```

## Configuration

### Using TaskToolCallbackProvider (Recommended)

The `TaskToolCallbackProvider` is a convenience builder that sets up both TaskTool and TaskOutputTool with sensible defaults:

```java
TaskToolCallbackProvider taskTools = TaskToolCallbackProvider.builder()
    .chatClientBuilder(chatClientBuilder)              // Required: ChatClient builder
    .agentDirectories("/path/to/custom/agents")        // Optional: Custom sub-agent definitions
    .skillsDirectories(".claude/skills")               // Optional: Skills for sub-agents
    .braveApiKey(System.getenv("BRAVE_API_KEY"))      // Optional: For web search
    .taskRepository(new DefaultTaskRepository())       // Optional: Custom task storage
    .build();
```

### Loading from Spring Resources

For better Spring Boot integration, you can load agents from Spring `Resource` objects:

```java
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

@Configuration
public class TaskConfig {

    @Autowired
    private ResourceLoader resourceLoader;

    @Bean
    public TaskToolCallbackProvider taskTools(ChatClient.Builder chatClientBuilder) {
        Resource agentsResource = resourceLoader.getResource("classpath:.claude/agents");

        return TaskToolCallbackProvider.builder()
            .chatClientBuilder(chatClientBuilder)
            .agentResource(agentsResource)
            .build();
    }
}
```

Loading multiple resources:

```java
@Bean
public TaskToolCallbackProvider taskTools(ChatClient.Builder chatClientBuilder) {
    List<Resource> agentResources = List.of(
        resourceLoader.getResource("classpath:.claude/agents"),
        resourceLoader.getResource("file:${user.home}/.claude/agents")
    );

    return TaskToolCallbackProvider.builder()
        .chatClientBuilder(chatClientBuilder)
        .agentResources(agentResources)
        .build();
}
```

### Manual Configuration

For more control, configure TaskTool and TaskOutputTool separately:

```java
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;

// Shared repository for task management
TaskRepository taskRepository = new DefaultTaskRepository();

// Core tools for sub-agents
List<ToolCallback> subAgentTools = List.of(
    FileSystemTools.builder().build(),
    GrepTool.builder().build(),
    GlobTool.builder().build(),
    new ShellTools()
);

// Configure TaskTool
ToolCallback taskTool = TaskTool.builder()
    .chatClientBuilder(chatClientBuilder)
    .taskRepository(taskRepository)
    .tools(subAgentTools)
    .addTaskDirectory("/path/to/custom/agents")  // Optional: path-based
    .build();

// Alternative with Spring Resources
Resource agentsResource = resourceLoader.getResource("classpath:.claude/agents");
ToolCallback taskTool = TaskTool.builder()
    .chatClientBuilder(chatClientBuilder)
    .taskRepository(taskRepository)
    .tools(subAgentTools)
    .addTasksResource(agentsResource)  // Optional: resource-based
    .build();

// Configure TaskOutputTool
ToolCallback taskOutputTool = TaskOutputTool.builder()
    .taskRepository(taskRepository)
    .build();

// Register with main chat client
ChatClient chatClient = chatClientBuilder
    .defaultToolCallbacks(taskTool, taskOutputTool)
    .build();
```

## Creating Custom Sub-Agents

### File Structure

Custom sub-agents are defined as Markdown files with YAML front matter:

```
project-root/
├── .claude/
│   └── agents/
│       ├── code-reviewer.md
│       ├── test-runner.md
│       └── spring-ai-expert.md
```

### Sub-Agent File Format

```markdown
---
name: your-sub-agent-name
description: When and how to use this subagent. Include trigger keywords and example scenarios.
tools: Read, Edit, Grep, Glob  # Optional: inherits all if omitted
model: sonnet                   # Optional: sonnet, opus, haiku
---

# Your Sub-Agent's System Prompt

You are a [role description]. You specialize in [domain].

**Your Primary Responsibilities:**
1. [Responsibility 1]
2. [Responsibility 2]

**Guidelines:**
- [Guideline 1]
- [Guideline 2]

**Process:**
1. [Step 1]
2. [Step 2]
```

### Configuration Fields

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Unique identifier (lowercase with hyphens) |
| `description` | Yes | Natural language purpose description with usage examples |
| `tools` | No | Comma-separated list of tool names (inherits all if omitted) |
| `model` | No | Model preference: `sonnet`, `opus`, `haiku` |

### Example: Code Reviewer Sub-Agent

```markdown
---
name: code-reviewer
description: Expert code reviewer. Use proactively after writing or modifying code. Focuses on code quality, security, and best practices.
tools: Read, Grep, Glob, Bash
model: sonnet
---

You are a senior code reviewer with expertise in software quality and security.

**When Invoked:**
1. Run `git diff` to see recent changes
2. Focus analysis on modified files
3. Check context of surrounding code

**Review Checklist:**
- Code clarity and readability
- Proper naming conventions
- No code duplication
- Comprehensive error handling
- Security (no exposed secrets, SQL injection, XSS)
- Input validation
- Test coverage
- Performance considerations

**Organize Feedback By Priority:**
- **Critical Issues**: Security vulnerabilities, bugs (must fix)
- **Warnings**: Code smells, maintenance issues (should fix)
- **Suggestions**: Style improvements, optimizations (consider)

**Output Format:**
Provide clear, actionable feedback with file references and line numbers.
```

### Example: Spring AI Expert Sub-Agent

From the [subagent-demo](../../examples/subagent-demo):

```markdown
---
name: spring-ai-expert
description: Use when user asks about Spring AI framework, features, configuration, API methods, integration, or troubleshooting.
model: sonnet
---

You are a Spring AI Expert with deep expertise in the Spring AI framework.

**Your Primary Resources:**
- Official Spring AI Reference: https://docs.spring.io/spring-ai/reference/index.html
- Spring AI GitHub: https://github.com/spring-projects/spring-ai

**Core Responsibilities:**
1. Answer questions accurately by consulting official documentation
2. Explore documentation thoroughly for specific topics
3. Examine source code when needed for implementation details
4. Provide contextual guidance with code examples
5. Cover key Spring AI areas (chat clients, embeddings, RAG, function calling, etc.)
6. Troubleshooting support with debugging approaches

**Quality Assurance:**
- Always verify information against official sources
- Provide links to specific documentation sections
- Distinguish documented features from inferred behavior
```

## Usage Patterns

### Automatic Delegation

The main agent automatically delegates to sub-agents based on:

```java
// Example: Agent automatically uses Explore sub-agent
String response = chatClient
    .prompt("How does authentication work in this codebase?")
    .call()
    .content();
// Main agent recognizes this as exploration task and delegates to Explore sub-agent
```

To encourage automatic delegation, use these keywords in the `description` field:
- "Use proactively"
- "MUST BE USED when"
- Include specific trigger phrases and example scenarios

### Explicit Invocation

Users can explicitly request specific sub-agents:

```java
// Example: Explicit sub-agent request
String response = chatClient
    .prompt("Use the code-reviewer subagent to review my recent changes")
    .call()
    .content();
```

### Background Execution

Long-running tasks can execute in the background:

```java
// Main agent can launch background sub-agents
String response = chatClient
    .prompt("Run the test-runner in the background and let me know when tests complete")
    .call()
    .content();

// TaskTool will set run_in_background=true
// Returns task_id for later retrieval

// Later, retrieve results via TaskOutputTool
String results = chatClient
    .prompt("Get the results from task task_12345")
    .call()
    .content();
```

### Tool Parameters

When the main agent calls TaskTool, it uses these parameters:

```java
public record TaskCall(
    String description,        // Short 3-5 word description
    String prompt,            // The task for the sub-agent
    String subagent_type,     // Which sub-agent to use
    String model,             // Optional: override model
    String resume,            // Optional: resume previous sub-agent
    Boolean run_in_background // Optional: run async
) {}
```

## Background Task Management

### TaskRepository

The `TaskRepository` manages background task execution:

```java
public interface TaskRepository {
    BackgroundTask putTask(String taskId, Supplier<String> taskExecution);
    BackgroundTask getTasks(String taskId);
}
```

### Default Implementation

```java
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;

// Uses CompletableFuture for async execution
TaskRepository repository = new DefaultTaskRepository();
```

### Custom Implementation

For distributed systems or persistence:

```java
@Component
public class RedisTaskRepository implements TaskRepository {

    @Autowired
    private RedisTemplate<String, BackgroundTask> redisTemplate;

    @Override
    public BackgroundTask putTask(String taskId, Supplier<String> taskExecution) {
        // Store in Redis, execute via message queue, etc.
        return createDistributedTask(taskId, taskExecution);
    }

    @Override
    public BackgroundTask getTasks(String taskId) {
        return redisTemplate.opsForValue().get(taskId);
    }
}
```

## Complete Example

See the [subagent-demo](../../examples/subagent-demo) for a complete working example:

```java
@SpringBootApplication
public class Application {

    @Value("${app.agent.skills.paths}")
    List<String> skillPaths;

    @Value("${BRAVE_API_KEY:#{null}}")
    String braveApiKey;

    @Bean
    CommandLineRunner demo(ChatClient.Builder chatClientBuilder) {
        return args -> {
            // Configure Task tools with custom agents
            var taskTools = TaskToolCallbackProvider.builder()
                .agentDirectories("src/main/resources/agents")
                .skillsDirectories(skillPaths)
                .braveApiKey(braveApiKey)
                .chatClientBuilder(chatClientBuilder.clone()
                    .defaultAdvisors(new LoggingAdvisor()))
                .build();

            // Build main chat client
            ChatClient chatClient = chatClientBuilder
                .defaultToolCallbacks(taskTools)
                .defaultTools(
                    FileSystemTools.builder().build(),
                    GrepTool.builder().build(),
                    GlobTool.builder().build(),
                    new ShellTools(),
                    TodoWriteTool.builder().build()
                )
                .defaultAdvisors(
                    ToolCallAdvisor.builder().build(),
                    MessageChatMemoryAdvisor.builder(
                        MessageWindowChatMemory.builder().maxMessages(500).build()
                    ).build()
                )
                .build();

            // Interactive chat loop
            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print("\nUSER: ");
                    String response = chatClient
                        .prompt(scanner.nextLine())
                        .call()
                        .content();
                    System.out.println("\nASSISTANT: " + response);
                }
            }
        };
    }
}
```

## Best Practices

### 1. Design Focused Sub-Agents

Each sub-agent should have a single, clear responsibility:

```markdown
---
name: test-runner
description: Runs tests and fixes failures. Use proactively after code changes.
tools: Bash, Read, Edit, Grep
---
```

### 2. Provide Detailed System Prompts

Include specific instructions, examples, and constraints:

```markdown
**Process:**
1. Run tests: `mvn test`
2. Analyze failures in test output
3. Read failing test code
4. Identify root cause
5. Implement minimal fix
6. Re-run tests to verify

**Constraints:**
- Preserve test intent and coverage
- Don't skip or disable tests
- Don't modify test assertions unless clearly wrong
```

### 3. Limit Tool Access

Grant only necessary tools for security and focus:

```markdown
---
tools: Read, Grep, Glob, Bash  # Read-only tools for exploration
---
```

### 4. Use Descriptive Names

Choose clear, self-documenting names:
- ✅ `spring-ai-expert`, `code-reviewer`, `test-runner`
- ❌ `helper`, `agent1`, `utility`

### 5. Include Usage Examples

Help the main agent understand when to delegate:

```markdown
---
description: Use when user asks about Spring AI. Examples:
  - "How do I configure embeddings in Spring AI?"
  - "What vector stores does Spring AI support?"
  - "Debug my Spring AI RAG pipeline"
---
```

### 6. Version Control Custom Agents

Commit project-specific agents for team collaboration:

```bash
git add .claude/agents/
git commit -m "Add code-reviewer and test-runner sub-agents"
```

### 7. Monitor Sub-Agent Performance

Add logging to understand sub-agent usage:

```java
public class SubAgentLoggingAdvisor implements CallAroundAdvisor {

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest,
                                     CallAroundAdvisorChain chain) {
        // Log sub-agent invocations
        advisedRequest.toolCalls().stream()
            .filter(tc -> "Task".equals(tc.name()))
            .forEach(tc -> log.info("Sub-agent called: {}", tc.arguments()));

        return chain.nextAroundCall(advisedRequest);
    }
}
```

## Advanced Features

### Resumable Sub-Agents

Continue long-running research across multiple interactions:

```java
// First invocation
String response1 = chatClient
    .prompt("Use code-analyzer to review authentication module")
    .call()
    .content();
// Returns: "Analysis complete. Agent ID: agent_abc123"

// Resume with additional context
String response2 = chatClient
    .prompt("Resume agent agent_abc123 and now analyze authorization logic")
    .call()
    .content();
```

**Implementation:** Sub-agent maintains conversation history in its context window.

### Model Override

Specify different models for different sub-agents:

```markdown
---
name: quick-explorer
model: haiku  # Fast, low-latency for simple searches
---
```

```markdown
---
name: deep-analyzer
model: opus  # Most capable for complex analysis
---
```

### Tool Filtering

Sub-agents inherit only specified tools from parent:

```java
// Parent has many tools
List<ToolCallback> allTools = List.of(
    FileSystemTools.builder().build(),
    new ShellTools(),
    GrepTool.builder().build(),
    // ... many more
);

// Sub-agent definition limits to specific tools
---
name: read-only-explorer
tools: Read, Grep, Glob  # Only these 3 tools available
---
```

## Troubleshooting

### Sub-Agent Not Being Used

**Problem:** Main agent not delegating to custom sub-agent.

**Solutions:**
1. Make description more specific with trigger keywords
2. Add "Use proactively" or "MUST BE USED when" to description
3. Include concrete examples in description
4. Verify sub-agent file is in correct directory
5. Check sub-agent name matches in both filename and front matter

### Sub-Agent Exceeding Scope

**Problem:** Sub-agent performing unwanted actions.

**Solutions:**
1. Limit tools in front matter: `tools: Read, Grep, Glob`
2. Add explicit constraints in system prompt
3. For read-only: Use Explore sub-agent as template
4. Add "NEVER" statements: "NEVER modify files", "NEVER run npm install"

### Background Tasks Not Completing

**Problem:** Background sub-agent tasks hanging or timing out.

**Solutions:**
1. Check TaskRepository implementation
2. Verify timeout settings in TaskOutputTool call
3. Add logging to task execution
4. Consider switching to synchronous execution for debugging

## API Reference

### TaskTool

```java
public class TaskTool {

    public static Builder builder() { ... }

    public static class Builder {
        // Required
        Builder chatClientBuilder(ChatClient.Builder chatClientBuilder);
        Builder taskRepository(TaskRepository taskRepository);

        // Optional
        Builder tools(List<ToolCallback> tools);
        Builder tools(ToolCallback tool);
        Builder addTaskDirectory(String taskRootDirectory);
        Builder addTaskDirectories(List<String> taskRootDirectories);
        Builder addTasksResource(Resource tasksRootPath);
        Builder addTasksResources(List<Resource> tasksRootPaths);
        Builder taskDescriptionTemplate(String template);

        ToolCallback build();
    }
}
```

### TaskOutputTool

```java
public class TaskOutputTool {

    public static Builder builder() { ... }

    public static class Builder {
        // Required
        Builder taskRepository(TaskRepository taskRepository);

        // Optional
        Builder taskDescriptionTemplate(String template);

        ToolCallback build();
    }
}
```

### TaskToolCallbackProvider

```java
public class TaskToolCallbackProvider implements ToolCallbackProvider {

    public static Builder builder() { ... }

    public static class Builder {
        // Required
        Builder chatClientBuilder(ChatClient.Builder chatClientBuilder);

        // Optional
        Builder taskRepository(TaskRepository taskRepository);
        Builder agentDirectories(List<String> agentDirectories);
        Builder agentDirectories(String agentDirectory);
        Builder agentResource(Resource agentRootPath);
        Builder agentResources(List<Resource> agentRootPaths);
        Builder skillsDirectories(List<String> skillsDirectories);
        Builder skillsDirectories(String skillsDirectory);
        Builder braveApiKey(String braveApiKey);

        TaskToolCallbackProvider build();
    }
}
```

## Related Tools

- [**FileSystemTools**](FileSystemTools.md) - File operations for sub-agents
- [**GrepTool**](GrepTool.md) - Code search capabilities
- [**GlobTool**](GlobTool.md) - File pattern matching
- [**ShellTools**](ShellTools.md) - Command execution
- [**SkillsTool**](SkillsTool.md) - Reusable knowledge modules for sub-agents

## References

- [Claude Code Sub-Agents Documentation](https://code.claude.com/docs/en/sub-agents)
- [Spring AI Documentation](https://docs.spring.io/spring-ai/reference/)
- [Example: subagent-demo](../../examples/subagent-demo)
