# Subagent Pattern - Tech Due Diligence Assistant

A multi-agent example demonstrating the **TaskTool** pattern: a main orchestrator agent that delegates complex work to specialized sub-agents.

## Overview

The **Tech Due Diligence Assistant** helps evaluate software projects by combining:

- **Codebase analysis**: Structure, dependencies, patterns, technical debt
- **Web research**: Documentation, alternatives, benchmarks, ecosystem

The main agent uses `write_todos` for planning and delegates to sub-agents via the **Task** and **TaskOutput** tools.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                  Tech Due Diligence Assistant                    │
│  (Orchestrator: write_todos, Task, TaskOutput, glob, grep, web)  │
└────────────────────────────┬────────────────────────────────────┘
                              │ delegates via Task tool
    ┌─────────────────────────┼─────────────────────────┬──────────────────┐
    ▼                         ▼                         ▼                  ▼
┌──────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐
│codebase-     │  │ web-researcher  │  │ general-purpose │  │ dependency-analyzer  │
│explorer      │  │ web_fetch       │  │ glob, grep, web │  │ (API-defined)        │
│glob, grep    │  │                 │  │                 │  │ glob, grep           │
│(Markdown)    │  │ (Markdown)      │  │ (Markdown)      │  └─────────────────────┘
└──────────────┘  └─────────────────┘  └─────────────────┘
```

## Sub-Agents

Sub-agents can be defined in two ways:

### 1. Markdown (file-based)

| Agent | Tools | Use Case |
|-------|-------|----------|
| **codebase-explorer** | glob_search, grep_search | Find files, search code, analyze structure |
| **web-researcher** | web_fetch | Fetch URLs, research docs, compare technologies |
| **general-purpose** | glob_search, grep_search, web_fetch | Combined code + web analysis |

Defined in `src/main/resources/agents/*.md` with YAML front matter.

### 2. API (programmatic)

| Agent | Tools | Use Case |
|-------|-------|----------|
| **dependency-analyzer** | glob_search, grep_search | Analyze dependencies, version conflicts, outdated libs |

Defined in Java via AgentScope `ReActAgent` and `AgentScopeAgent`, registered with `TaskToolsBuilder.subAgent()` and the orchestrator graph.

## Running

### Prerequisites

- JDK 17+
- `AI_DASHSCOPE_API_KEY` environment variable set

### Interactive Mode

```bash
# From repo root - run with interactive chat
AI_DASHSCOPE_API_KEY=your_key ./mvnw -pl agentscope-examples/multiagent-patterns/subagent spring-boot:run \
  -Dspring-boot.run.arguments="--subagent.run-interactive=true"
```

Or set in `application.yml`:

```yaml
subagent:
  run-interactive: true
```

### Example Prompts

- **Simple**: "Find all Java files in this project"
- **Codebase**: "What frameworks and dependencies does this project use?"
- **Web**: "Fetch https://spring.io/projects/spring-ai and summarize its features"
- **Dependency (API sub-agent)**: "Analyze this project's dependencies for version conflicts and outdated libraries"
- **Combined**: "Analyze this codebase for Spring usage, then research Spring AI alternatives and compare with our current setup"

### Programmatic Usage

The orchestrator and dependency-analyzer are **AgentScopeAgent** beans; the graph invokes the orchestrator. Use `OrchestratorService` to run the full flow:

```java
@Autowired
OrchestratorService orchestratorService;

String answer = orchestratorService.run(
    "Analyze this codebase for technical debt and research Spring AI documentation");
```

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `subagent.workspace-path` | `${user.dir}` | Root path for glob_search and grep_search |
| `subagent.run-interactive` | `false` | Run interactive chat on startup |

## Key Components

- **TaskToolsBuilder**: Builds Task + TaskOutput tools. Supports both:
  - **Markdown**: `addAgentResource()` / `addAgentDirectory()` loads specs from `.md` files
  - **API**: `subAgent(type, ReactAgent)` registers programmatically defined ReactAgents
- **TodoListInterceptor**: Injects write_todos tool and system prompt for task planning
- **Agent specs (Markdown)**: `name`, `description`, `tools` (comma-separated) in YAML front matter

## Related

- [subagents.md](../../../multiagents/subagents.md) - Subagent architecture documentation
- [spring-ai-agent-utils subagent-demo](../../../multiagents/spring-ai-agent-utils/examples/subagent-demo) - Similar pattern with Spring AI community tools
