---
title: "Tool"
description: "Define, register, and manage the capabilities an agent can call"
---

## Overview

Tools are how an agent acts on the world — running business operations, calling APIs, reading and writing data. Each tool exposes itself to the LLM as a JSON Schema, and the agent invokes it through a unified interface.

AgentScope organizes tool-related building blocks under three concepts:

- **Tool** — any object implementing the `AgentTool` contract (typically by extending `ToolBase`) or any plain class whose methods are annotated with `@Tool`. Java refers to the latter as *reflective function tools* — `Toolkit#registerTool(Object)` registers them by reflection automatically.
- **Toolkit** — the container that registers tools, MCP clients, and skills, exposes their JSON schemas to the model, and dispatches each tool call to the matching tool object.
- **Tool Group** — a named bundle of tools / MCP clients / skills that can be activated or deactivated as a unit. The agent uses a built-in meta tool to switch groups at runtime, keeping the context focused.

```java
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new TodoTools());
toolkit.registerTool(new MyCustomTools());
```

When you only call `registerTool(Object)`, every `@Tool` method on the registered object joins the reserved `"basic"` group — always active. Add MCP clients, tool groups, or skills to extend the agent further — see the sections below.

## Java tools

A Java tool is any object satisfying the `AgentTool` contract. AgentScope ships an abstract base class `ToolBase` for declaring tools with explicit parameter schemas, plus a reflective adapter that wraps plain methods into tools.

### AgentTool / ToolBase contract

`ToolBase` is the abstract `AgentTool` implementation. The table below lists its properties and methods.

Properties exposed to the agent and runtime:

| Method | Type | Description |
|--------|------|-------------|
| `getName()` | `String` | The tool name shown to the agent |
| `getDescription()` | `String` | Description shown to the agent |
| `getParameters()` | `Map<String, Object>` | JSON Schema describing the parameters |
| `isConcurrencySafe()` | `boolean` | Can the tool be called concurrently? |
| `isReadOnly()` | `boolean` | Is the tool read-only / side-effect-free? |
| `isExternalTool()` | `boolean` | When `true`, execution is delegated externally (see [external execution](#external-execution-tools)) |
| `isStateInjected()` | `boolean` | When `true`, the framework injects `AgentState` as an extra parameter |
| `isMcp()` | `boolean` | Did the tool come from an MCP server? |
| `getMcpName()` | `String` | The MCP server name when `isMcp()` is `true` |

Methods that integrate with the execution flow and the permission system:

| Method | Required | Description |
|--------|----------|-------------|
| `checkPermissions(toolInput, context)` | yes | Runtime permission check before execution; returns `Mono<PermissionDecision>` |
| `matchRule(ruleContent, toolInput)` | optional | Custom rule matcher for the permission system; returns `boolean` |
| `generateSuggestions(toolInput)` | optional | Generate suggested rules from the current invocation; returns `List<PermissionRule>` |
| `callAsync(param)` | optional | Tool execution; returns `Mono<ToolResultBlock>`. External tools do not implement this. |

### Built-in tools

AgentScope currently ships these built-in tools:

| Tool | Description | Read-only |
|------|-------------|-----------|
| `TodoTools.todoWrite` | Maintain a structured task list for the current session (full-list-replace semantics) | no |

Usage:

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new io.agentscope.core.tool.builtin.TodoTools());
```

:::{note}
The `Toolkit` automatically registers a `reset_tools` meta tool and a `Skill` viewer when extra tool groups or skills are present — you don't need to instantiate them manually. See [self-managed tools](#self-managed-tools) and [Skill](#skill).
:::

### Custom tools (annotation-based)

The lightest-weight way: annotate plain methods with `@Tool` and `@ToolParam`, then call `Toolkit#registerTool(Object)`. The framework derives the JSON schema from Java types and the `description` for the agent.

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class SimpleTools {

    @Tool(
            name = "get_current_time",
            description = "Returns the current time in a given IANA timezone.",
            readOnly = true,
            concurrencySafe = true)
    public String getCurrentTime(
            @ToolParam(name = "timezone", description = "IANA timezone, e.g. Asia/Shanghai")
                    String timezone) {
        return LocalDateTime.now(ZoneId.of(timezone))
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new SimpleTools());
```

Common `@Tool` attributes:

| Attribute | Type | Description |
|-----------|------|-------------|
| `name` | `String` | Tool name (defaults to the method name) |
| `description` | `String` | Description shown to the agent |
| `readOnly` | `boolean` | Whether the tool is read-only (default `false`) |
| `concurrencySafe` | `boolean` | Whether the tool is safe for concurrent calls (default `false`) |
| `stateInjected` | `boolean` | Inject `AgentState` as an extra parameter (default `false`) |
| `dangerousFiles` / `dangerousDirectories` | `String[]` | Append custom dangerous paths |
| `converter` | `Class<? extends ToolResultConverter>` | Custom conversion of return values into `ToolResultBlock` |

### Custom tools (extending `ToolBase`)

When you need a custom permission policy, external execution, or a more complex schema, extend `ToolBase`:

```java
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionContext;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public class WebSearchTool extends ToolBase {

    public WebSearchTool() {
        super(
                ToolBase.builder()
                        .name("WebSearch")
                        .description("Search the web for information on a given query.")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of(
                                                "type", "string",
                                                "description", "The search query.")),
                                "required", List.of("query")))
                        .readOnly(true)
                        .concurrencySafe(true));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, ToolExecutionContext context) {
        return Mono.just(PermissionDecision.allow("Web search is read-only."));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String query = (String) param.getInput().get("query");
        return doSearchAsync(query)
                .map(text ->
                        ToolResultBlock.builder()
                                .id(param.getId())
                                .name(getName())
                                .output(List.of(TextBlock.builder().text(text).build()))
                                .build());
    }
}
```

### External execution tools

External-execution tools delegate the actual work outside the agent runtime — typically to a human operator or an external system. The agent emits `RequireExternalExecutionEvent` and pauses until the result is fed back via `ExternalExecutionResultEvent`.

This pattern is the foundation of [human-in-the-loop](./agent.md#human-in-the-loop) flows — some actions need human approval or human execution.

To create an external tool, set `externalTool` to `true` and skip implementing `callAsync`:

```java
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolExecutionContext;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public class HumanApprovalTool extends ToolBase {

    public HumanApprovalTool() {
        super(
                ToolBase.builder()
                        .name("HumanApproval")
                        .description("Request human approval for a sensitive operation.")
                        .inputSchema(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "action", Map.of("type", "string"),
                                        "reason", Map.of("type", "string")),
                                "required", List.of("action", "reason")))
                        .readOnly(false)
                        .concurrencySafe(true)
                        .externalTool(true));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, ToolExecutionContext context) {
        return Mono.just(PermissionDecision.allow("External tool dispatch is always allowed."));
    }
}
```

Runnable examples: `agentscope-examples/documentation/.../tool/ToolBaseExample.java`, `tool/ToolExecutionContextExample.java`.

## Receiving context

The [`RuntimeContext`](./agent.md#runtimecontext-per-call-context) passed to `agent.call(msgs, runtimeContext)` is forwarded to every tool invocation in that reply. Tools can read it in two ways: annotation-based tools through automatic injection, and `ToolBase.callAsync` through `ToolCallParam`.

### Automatic injection (`@Tool` methods)

Inside a `@Tool` method, any parameter **without `@ToolParam`** is treated as framework-injected. The resolution order:

| Parameter type | Source |
|----------------|--------|
| `ToolEmitter` | Streaming emitter (no-op when none configured) |
| `Agent` | The current agent instance |
| `AgentState` | `agent.getAgentState()` (also the target of `@Tool(stateInjected = true)`) |
| `RuntimeContext` | The current per-call context |
| `ToolExecutionContext` | `runtimeContext.asToolExecutionContext()` (compatibility shim, deprecated) |
| Any other user POJO type | `runtimeContext.get(ParamType.class)` — i.e. an object the caller registered via `RuntimeContext.builder().put(ParamType.class, value)` |

"User POJO" means: no `@ToolParam`, not primitive, not `ContentBlock` / `Msg`, not under `java.*` / `javax.*`. Every other parameter (those with `@ToolParam`, or that fall outside the above types) is read from the LLM-supplied JSON by name.

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public record UserContext(String username, String locale) {}

public class PersonalizedTools {

    @Tool(name = "greet", description = "Greet the user with a custom greeting")
    public String greet(
            @ToolParam(name = "greeting", description = "Greeting word, e.g. 'Hello'")
                    String greeting,                  // ← supplied by the model
            UserContext userCtx) {                    // ← injected by the framework
        return greeting + ", " + (userCtx == null ? "unknown" : userCtx.username()) + "!";
    }
}
```

The caller registers the POJO by type once; every `call` then routes the matching instance to any tool that asks for it:

```java
RuntimeContext ctx =
        RuntimeContext.builder()
                .put(UserContext.class, new UserContext("alice", "en"))
                .userId("alice")
                .build();

agent.call(List.of(new UserMessage("Greet me.")), ctx).block();
```

The model never sees `userCtx` — it is not part of the tool's JSON schema. Full example: `agentscope-examples/documentation/.../tool/ToolExecutionContextExample.java`.

### Accessing context in `ToolBase.callAsync`

Tools that extend `ToolBase` read context through `ToolCallParam`:

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

public class TenantAwareTool extends ToolBase {

    public TenantAwareTool() {
        super(/* builder ... */);
    }

    @Override
    public Mono<io.agentscope.core.message.ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext rc = param.getRuntimeContext();
        String tenantId = rc != null ? rc.getUserId() : null;
        TenantConfig cfg = rc != null ? rc.get(TenantConfig.class) : null;
        // ... apply tenantId / cfg ...
    }
}
```

`ToolCallParam` also exposes `getAgent()`, `getInput()`, `getEmitter()`, `getToolUseBlock()`, and the deprecated `getContext()`. Prefer `getRuntimeContext()` in new code.

### Coordinating between hooks and tools

The `RuntimeContext` string layer (`put(String, Object)` / `get(String)`) is a short-lived channel between middleware and tools during a single `call` — a middleware can write at `onActing`/`onReasoning` and a tool that injects a `RuntimeContext` parameter reads it. The instance is unbound from the agent (along with all hooks) when the call finishes.

## MCP

AgentScope integrates with the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/), letting the agent talk to any MCP-compatible tool provider. The framework handles protocol negotiation, tool discovery, and result conversion.

Three transports are supported:

- **STDIO** — local process via stdin/stdout
- **SSE / Streamable HTTP** — remote HTTP long-connection

MCP tools are exposed in the toolkit under the namespace `mcp__{server_name}__{tool_name}` to avoid collisions; tools marked `readOnlyHint` are auto-allowed by the permission system.

### Registering MCP tools

Use `McpClientBuilder` to build an `McpClientWrapper`, then register it on the `Toolkit`:

::::{tab-set}
:::{tab-item} STDIO
```java
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

McpClientWrapper filesystem =
        McpClientBuilder.stdio()
                .name("filesystem")
                .command("mcp-server-filesystem")
                .args("--root", "/my/project")
                .build();

Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(filesystem).block();
```
:::
:::{tab-item} Streamable HTTP
```java
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

McpClientWrapper weather =
        McpClientBuilder.streamableHttp()
                .name("weather")
                .url("https://api.weather.com/mcp")
                .header("Authorization", "Bearer xxx")
                .build();

Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(weather).block();
```
:::
:::{tab-item} SSE
```java
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

McpClientWrapper search =
        McpClientBuilder.sse()
                .name("search")
                .url("https://api.search.com/mcp/sse")
                .build();

Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(search).block();
```
:::
::::

Runnable examples: `agentscope-examples/documentation/.../mcp/McpStdioExample.java`, `mcp/McpSseExample.java`, `mcp/McpStreamableHttpExample.java`.

## Skill

Skills are markdown-based instruction sets that extend an agent's capabilities without writing new tool code. Each skill is a directory containing a `SKILL.md` file with frontmatter metadata and detailed instructions.

Unlike tools, skills are not directly callable. The agent reads skill instructions through an auto-registered `Skill` viewer, then carries them out using whatever tools it already has.

### Registering skills

Attach one or more `AgentSkillRepository` directly via `ReActAgent.builder().skillRepository(...)`. At `build()` time the builder auto-installs `DynamicSkillMiddleware`, which rebuilds the skill prompt and tool groups from the configured sources on every `call()`:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import java.nio.file.Paths;

ReActAgent agent =
        ReActAgent.builder()
                .name("SkillCreator")
                .sysPrompt("...")
                .model(model)
                .skillRepository(new FileSystemSkillRepository(Paths.get("/path/to/skills"), false))
                .build();
```

Multiple `skillRepository(...)` calls append in order (low → high priority); when two repositories expose a skill with the same name, the later entry wins. Use `skillRepositories(List<AgentSkillRepository>)` to replace the list. Pass `dynamicSkillsEnabled(false)` to opt out of the auto-installed middleware (handy when an outer orchestrator like `HarnessAgent` attaches its own subclass).

Reference implementations: `agentscope-examples/documentation/.../skill/AgentSkillExample.java`, `skill/SkillWithToolGroupExample.java`.

### How skills work

When skills are present, the `Toolkit` performs a two-phase setup.

Initialisation:

- The toolkit scans every registered skill source and collects each skill's name, description, and directory.
- It auto-registers the built-in `Skill` viewer.
- It assembles a system-prompt fragment listing the available skills (names + descriptions) and instructing the agent to read full content via the `Skill` viewer.

At runtime:

- The agent picks a skill by name and calls the `Skill` viewer.
- The viewer reads the matching `SKILL.md` and returns the full markdown.
- The agent follows the instructions using its existing tools.

:::{note}
A skill is not a tool — the agent cannot call it directly. The agent must read the instructions via the `Skill` viewer first, then act on them with other tools.
:::

## Self-managed tools

The built-in **meta tool** (`reset_tools`) lets the agent self-manage which tool groups are active at runtime, keeping the context focused — only the tools relevant to the current task are exposed to the model.

### Defining tool groups

`ToolGroup` is a named bundle of tools / MCP clients / skills. Register the group on the `Toolkit` and turn on the meta tool through the builder:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolGroup;
import io.agentscope.core.tool.ToolGroupScope;

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new BasicTools());

ToolGroup database =
        new ToolGroup(
                "database",
                "Tools for database operations.",
                ToolGroupScope.SESSION,
                /* active = */ false);
database.addTool("db_query");
database.addTool("db_migrate");
toolkit.registerTool(new DatabaseTools());
toolkit.registerToolGroup(database);

ToolGroup deployment =
        new ToolGroup(
                "deployment",
                "Tools for deploying services.",
                ToolGroupScope.SESSION,
                /* active = */ false);
deployment.addTool("deploy");
deployment.addTool("rollback");
toolkit.registerTool(new DeploymentTools());
toolkit.registerToolGroup(deployment);

ReActAgent agent =
        ReActAgent.builder()
                .name("router")
                .toolkit(toolkit)
                .enableMetaTool(true)
                .build();
```

`ToolGroup` takes a name, a description, a scope (`ToolGroupScope`), and an initial active flag. The reserved name `"basic"` is auto-populated by `Toolkit#registerTool(Object)` and is always active.

### Using the meta tool

Whenever there's at least one non-basic tool group and `enableMetaTool(true)` is on, the `Toolkit` auto-registers `reset_tools` and exposes its schema to the agent. Each non-basic group becomes a boolean field; calling the meta tool declares the desired final state.

Runtime behavior:

- Tools in the `"basic"` group are always exposed; the meta tool does not touch them.
- Each `reset_tools` call **wholly overwrites** the active set — any non-basic group not explicitly set to `true` is deactivated, regardless of its previous state.
- For each group that just became active, its description and (if provided) instructions are spliced into the meta tool's return value, telling the agent how to use it correctly.
- Tools in inactive groups do not appear in the agent's tool schema, leaving more context for the active toolset.

:::{warning}
The meta tool's input represents the **final state** of all groups, not a delta. Any group not explicitly set to `true` is deactivated regardless of previous state.
:::

## Further reading

::::{grid} 2

:::{grid-item-card} Agent
:link: ./agent.html

How agents orchestrate tool calls in the ReAct loop
:::
  :::{grid-item-card} Permission System
:link: ./permission-system.html

Fine-grained control over which tools execute and when
:::
  :::{grid-item-card} Middleware
:link: ./middleware.html

Use onion middlewares to intercept and rewrite tool calls
:::
  :::{grid-item-card} Human-in-the-Loop
:link: ./agent.html#human-in-the-loop

External execution tools and approval workflows
:::

::::
