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
