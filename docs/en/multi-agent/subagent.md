# Subagents

Subagents are **specialized agents** that a main **orchestrator** delegates work to. The orchestrator does not execute the work itself; it calls a **Task** tool with a sub-agent type and a task description. The system runs the chosen sub-agent in an **isolated context** (its own system prompt and tools), then returns the result to the orchestrator. This keeps the main conversation focused and avoids context bloat, while still allowing multiple domains (e.g. codebase exploration, web research, dependency analysis) to be handled by dedicated agents.

This pattern is sometimes called a **dispatcher–worker** or **hierarchical** model: one “manager” agent that delegates to “specialist” sub-agents on demand.

## Why use subagents?

| Benefit | Description |
|--------|----------------|
| **Context preservation** | Heavy or noisy work (e.g. large codebase search, many URLs) stays in the sub-agent’s context; only a summary or relevant excerpt returns to the main conversation. |
| **Specialized expertise** | Each sub-agent has a focused system prompt and a limited set of tools, so it behaves consistently for that domain. |
| **Reusability** | Sub-agents can be defined once (e.g. in Markdown files or as shared beans) and reused across flows or projects. |
| **Flexible definition** | You can define sub-agents in **Markdown** (file-based specs) for easy editing and versioning, or in **Java** (programmatic ReActAgent) for full control. |

Use the subagent pattern when you have **multiple distinct domains**, want **one orchestrator** to drive the workflow, and sub-agents do **not** need to talk directly to the user—they only report back to the orchestrator.

## How it works

1. **Orchestrator** has a tool named **Task** (and optionally **TaskOutput** for background tasks). It also has direct tools (e.g. `glob_search`, `grep_search`, `web_fetch`) for simple, single-step work.
2. When the user request is complex or domain-specific, the orchestrator **calls the Task tool** with:
   - **subagent_type**: which sub-agent to run (e.g. `codebase-explorer`, `web-researcher`)
   - **prompt**: the task description to send to that sub-agent
3. The system **looks up** the sub-agent by type, runs it with that prompt in an **isolated context** (its own system prompt and tools), and returns the sub-agent’s reply as the tool result.
4. The orchestrator uses that result (and optionally other tool results) to **synthesize** a final answer for the user.

Sub-agents are **stateless** per invocation: each Task call gets a fresh run. The orchestrator holds the conversation and any planning state; sub-agents do not see the full chat history.

## Defining sub-agents

You can define sub-agents in two ways.

### 1. Markdown (file-based)

Sub-agents are described in **Markdown files** with **YAML front matter**. The front matter defines the agent’s `name`, `description`, and `tools`; the body is the **system prompt** for that sub-agent.

**Location**: e.g. `src/main/resources/agents/*.md` (classpath).

**Format**:

```markdown
---
name: codebase-explorer
description: Fast agent for exploring codebases. Use for finding files, searching code, analyzing structure. Tools: glob_search, grep_search.
tools: glob_search, grep_search
---

You are a codebase exploration specialist. Your job is to explore and analyze codebases efficiently.

**Your capabilities:**
- Find files using glob patterns
- Search file contents using regex
- Analyze project structure and dependencies

**Guidelines:**
- Use glob_search first to understand layout, then grep_search for content
- Provide concise, structured findings
```

- **name**: Unique identifier; the orchestrator uses this as `subagent_type` when calling the Task tool.
- **description**: Tells the orchestrator (and the model) when to delegate to this sub-agent. Be specific so the main agent knows when to use it.
- **tools**: Comma-separated list of tool names this sub-agent can use. Only these tools are attached; others are not available, which keeps the sub-agent focused and safe.

The loader (e.g. `AgentSpecLoader`) reads these files and builds a ReActAgent per spec using a shared **Model** and a **default tools map** (tool name → instance). So all Markdown-defined agents share the same underlying tool implementations (e.g. `glob_search`, `grep_search`, `web_fetch`), but each spec restricts which ones that agent sees.

### 2. API (programmatic)

For full control, build a **ReActAgent** in Java and register it with the Task tools builder:

```java
ReActAgent dependencyAnalyzerReAct = ReActAgent.builder()
        .name("dependency-analyzer")
        .description("Analyzes project dependencies. Use for version conflicts, outdated libs, security.")
        .model(model)
        .sysPrompt(DEPENDENCY_ANALYZER_SYSTEM_PROMPT)
        .toolkit(depToolkit)  // e.g. glob_search, grep_search only
        .memory(new InMemoryMemory())
        .build();

TaskToolsBuilder.builder()
        .model(model)
        .defaultToolsByName(defaultToolsByName)
        .subAgent("dependency-analyzer", dependencyAnalyzerReAct)
        // ... addAgentResource(...) for Markdown specs
        .build();
```

The orchestrator can then call the Task tool with `subagent_type="dependency-analyzer"` and a task description. Programmatic sub-agents can use custom tools and logic that are not in the default tools map.

## Task tools builder (AgentScope example)

The example uses **TaskToolsBuilder** to create the **Task** and **TaskOutput** tools and to register all sub-agents:

1. **TaskToolsBuilder.builder()**
   - **model**: Used when building ReActAgents from Markdown specs.
   - **defaultToolsByName**: Map of tool name → instance (e.g. `glob_search`, `grep_search`, `web_fetch`). Markdown specs reference these by name in the `tools` field.
   - **taskRepository**: For background execution; required even if you only use synchronous Task calls.
   - **subAgent(type, ReActAgent)**: Register one programmatic sub-agent.
   - **addAgentResource(Resource)**: Load one Markdown spec from a classpath or file resource (e.g. `classpath:agents/codebase-explorer.md`).

2. **build()**  
   Resolves all Markdown specs into ReActAgents (via `AgentSpecLoader` and a factory), merges them with programmatic sub-agents, and returns a **TaskToolsResult** with:
   - **taskTool()**: The Task tool to register on the orchestrator’s toolkit.
   - **taskOutputTool()**: The TaskOutput tool for checking or retrieving results of background tasks.

3. **Orchestrator toolkit**  
   Register the Task and TaskOutput tools on the orchestrator’s Toolkit, along with any direct tools (glob, grep, web_fetch) the orchestrator can use itself. The orchestrator’s system prompt should describe when to delegate (which subagent_type for which kind of request) and when to use direct tools.

**Synchronous vs background**: By default, the Task tool runs the sub-agent and returns its reply. If you pass a flag like `run_in_background=true`, the tool returns a `task_id` instead; the orchestrator (or user) can later call **TaskOutput** with that `task_id` to get the result. This requires a **TaskRepository** to store in-flight tasks.

## Passing Custom Context and Parameters

In real-world applications, you may need to pass additional parameters (e.g., `userId`) to a sub-agent alongside the `message`. `SubAgentTool` supports two distinct parameter injection methods:

1. **LLM Dynamic Injection (Business Variables)**: Inferred by the LLM based on the user's chat context (e.g., target translation language, analysis depth).
2. **System Context Injection (Security Variables)**: Injected by the underlying system via `ToolExecutionContext`. This is completely transparent to the LLM and tamper-proof (e.g., `userId`, `tenantId`).

### 1. Declare parameters
First, declare custom parameters via `SubAgentConfig`. The framework strictly distinguishes between two types of parameters to ensure security and flexibility:

```java
SubAgentConfig config = SubAgentConfig.builder()
        // 1. Declare a business variable (via addParameter: visible to the LLM, inferred by the LLM based on the conversation)
        .addParameter("analysis_depth", Map.of("type", "string", "enum", List.of("basic", "detailed")), false)
        // 2. Declare a security variable (via addSystemParameter: invisible to the LLM, strictly injected by the underlying system)
        .addSystemParameter("userId")
        .build();

SubAgentTool tool = new SubAgentTool(agentProvider, config);
```

### 2. Examples of injection methods

#### Method 1: LLM Dynamic Injection (Business Variables)
Suitable for **business properties**. Variables declared via `addParameter` (such as `analysis_depth`) will be rendered into the JSON Schema passed to the LLM.
When the user says: *"Help me do an extremely deep code review"*, the LLM will automatically infer and generate the following call:
```json
{
  "message": "Review the codebase",
  "analysis_depth": "detailed" 
}
```

💡 Backend Intervention (Fallback Mechanism): Although business variables are inferred by the LLM, the framework still allows the backend to inject a parameter with the same name via ToolExecutionContext. If the system is in a degraded mode or requires special overrides, the value injected by the underlying system will forcibly override the LLM's inference, ensuring absolute backend control.

#### Method 2: System Context Injection (Security Variables)
Suitable for **sensitive security properties** (e.g., `userId`). Variables declared via `addSystemParameter` are **completely invisible** to the LLM. The system interceptor will securely inject them directly at runtime.
```java
// Register the context at the system entry point
ToolExecutionContext context = ToolExecutionContext.builder()
        .register("userId", String.class, "user_123") // Explicitly specify the type as String.class
        .build();

// Pass the context during execution
ToolCallParam param = ToolCallParam.builder()
        .toolUseBlock(toolUseBlock)
        .input(Map.of("message", "Check my order"))
        .context(context)
        .build();

tool.callAsync(param).subscribe();
```

> **🔒 Security and Priority**
> Because system parameters (such as `userId`) are declared via `addSystemParameter`, they will not appear in the Schema sent to the LLM. At runtime, the framework strictly follows the principle of **"Absolute Priority for System Context."** Even if a malicious user uses a Prompt Injection attack to force the LLM to forcibly output `"userId": "admin"` in the generated JSON, the underlying framework will **completely ignore** and discard the fake value passed by the LLM, extracting the real context strictly and only from the `ToolExecutionContext`. This fundamentally eliminates the risk of unauthorized access.

### 3. Retrieve parameters in the sub-agent
Regardless of the injection method, the parameters are ultimately and securely mounted in the `metadata` of the input message received by the sub-agent. The extraction method is identical:

```java
public Mono<Msg> call(List<Msg> messages) {
    Msg userMsg = messages.get(messages.size() - 1);
    Map<String, Object> metadata = userMsg.getMetadata();
    
    // Retrieve the system-injected security parameter
    String userId = (String) metadata.get("userId");
    // Retrieve the LLM-injected business parameter
    String depth = (String) metadata.get("analysis_depth");
    
    // ... execute specific logic based on these parameters ...
}
```

## Example: Tech Due Diligence Assistant

The AgentScope example implements a **Tech Due Diligence Assistant**: one orchestrator that delegates to four sub-agents.

| Sub-agent | Definition | Tools | Use case |
|-----------|------------|-------|----------|
| **codebase-explorer** | Markdown | glob_search, grep_search | Find files, search code, analyze structure |
| **web-researcher** | Markdown | web_fetch | Fetch URLs, research docs, compare technologies |
| **general-purpose** | Markdown | glob_search, grep_search, web_fetch | Combined code + web analysis |
| **dependency-analyzer** | API (Java) | glob_search, grep_search | Dependencies, version conflicts, outdated libs |

- **Orchestrator** system prompt describes when to use each sub-agent and when to use direct tools. It has Task, TaskOutput, glob_search, grep_search, web_fetch.
- **Markdown specs** live in `src/main/resources/agents/*.md` and are loaded with `TaskToolsBuilder.addAgentResource(res)`.
- **dependency-analyzer** is built as a ReActAgent and registered with `TaskToolsBuilder.subAgent("dependency-analyzer", dependencyAnalyzerReAct)`.

**Run interactively**:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/subagent spring-boot:run \
  -Dspring-boot.run.arguments="--subagent.run-interactive=true"
```

**Use in code**: Inject **OrchestratorService** and call `run(userMessage)`; the service invokes the graph that runs the orchestrator with the given input.

```java
@Autowired
OrchestratorService orchestratorService;

String answer = orchestratorService.run(
    "Analyze this codebase for technical debt and research Spring AI documentation");
```

**Configuration**: `subagent.workspace-path` (default `${user.dir}`) is the root path for glob_search and grep_search; `subagent.run-interactive` (default `false`) enables the interactive chat runner on startup.

## Subagents vs other patterns

- **Supervisor**: The supervisor pattern also has a central agent that calls specialists as tools, but there is **one tool per specialist** (e.g. `schedule_event`, `manage_email`) and often fewer, more stable roles. Subagents use a **single Task tool** parameterized by type and prompt, and support many sub-agents (including Markdown-defined) and optional background execution.
- **Agent as Tool**: Agent as Tool registers one sub-agent as one tool with a fixed name and signature. Subagents use a **dispatcher** (Task tool) that selects the sub-agent by name and passes a free-form task string; sub-agents are often defined in bulk via Markdown.
- **Routing**: A router classifies the input and sends it to one or more specialists, then synthesizes results; it usually does not maintain a long conversation. The subagent orchestrator maintains the conversation and can call Task multiple times (possibly different sub-agent types) in one turn or across turns.

## Best practices

- **Write clear sub-agent descriptions**: The orchestrator relies on `description` (and its own system prompt) to decide when to call which sub-agent. Include trigger phrases and example scenarios (e.g. “Use for finding files, searching code, analyzing structure”).
- **Limit tools per sub-agent**: In Markdown, list only the tools that sub-agent needs. This keeps behavior predictable and reduces the risk of misuse.
- **Keep system prompts focused**: The body of each Markdown file is the sub-agent’s system prompt. Define responsibilities, steps, and output format so the sub-agent returns useful, consistent results.
- **Version control Markdown specs**: Store `agents/*.md` in the repo so the team can add and refine sub-agents without code changes.

## Related Documentation

- [Supervisor](./supervisor.md) - One supervisor, one tool per specialist
- [Agent as Tool](../task/agent-as-tool.md) - Register a single agent as a tool
- [Pipeline](./pipeline.md) - Sequential and parallel composition
