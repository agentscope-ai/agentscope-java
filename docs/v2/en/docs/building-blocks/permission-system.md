---
title: "Permission System"
description: "Fine-grained control over which tools your agents can execute and when"
---

## Overview

The permission system (`io.agentscope.core.permission`) intercepts every tool call the agent makes and produces one of three decisions: **ALLOW**, **DENY**, or **ASK** (request user confirmation).

It combines static configuration with dynamic runtime analysis. Three components together decide the outcome:

- **Rules** — explicit allow / deny / ask patterns per tool and command, with the highest priority. Rules come from two sources: static configuration in `PermissionContextState`, or **suggested rules** added dynamically when the user accepts them at an ASK prompt. Suggested rules are auto-generated from the current invocation — once accepted, identical future calls are auto-handled without prompting.
- **Mode** — a global static policy set at configuration time; decides the default behaviour for calls that match no rule (e.g. `EXPLORE` makes the agent read-only, `DONT_ASK` silently denies anything not matching a rule).
- **Built-in Checks** — runtime analysis performed by the tool itself based on the actual input (implemented in `ToolBase#checkPermissions`). These are runtime checks rather than preconfigured patterns, so they are **non-bypassable** — they are not subject to mode or rules.

```{mermaid}
sequenceDiagram
    participant LLM
    participant PS as Permission System
    participant Tool
    participant User

    LLM->>PS: Tool Call
    Note over PS: Built-in Checks · Rules · Mode

    alt ALLOW
        PS->>Tool: execute
        Tool->>LLM: result
    else DENY
        PS->>LLM: denied
    else ASK + Suggestions
        PS->>User: ASK + Suggestions
        alt User approves
            User->>Tool: allow
            Tool->>LLM: result
            User-->>PS: accept suggested rule
        else User denies
            User->>PS: deny
            PS->>LLM: denied
        end
    end
```

:::{dropdown} Detailed decision flow
```{mermaid}
flowchart TD
    A([Tool Call]) --> B{Deny Rules?}
    B -->|Match| DENY([DENY])
    B -->|No Match| C{Ask Rules?}
    C -->|Match| ASK1([ASK])
    C -->|No Match| D{Tool-Specific Checks}
    D -->|EXPLORE + write op| DENY
    D -->|Dangerous path| ASK2([ASK])
    D -->|Pass| E{Allow Rules?}
    E -->|Match| ALLOW([ALLOW])
    E -->|No Match| F{"ACCEPT_EDITS + safe file op?"}
    F -->|Yes| ALLOW
    F -->|No| G{"Read-only Bash command?"}
    G -->|Yes| ALLOW
    G -->|No| H{BYPASS mode?}
    H -->|Yes| ALLOW
    H -->|No| I{DONT_ASK mode?}
    I -->|Yes| DENY
    I -->|No| ASK3([ASK])
    ASK1 --> S[Generate Suggestions]
    ASK2 --> S
    ASK3 --> S
    S --> U{User Confirms?}
    U -->|Approve| ALLOW
    U -->|Deny| DENY
    U -->|Apply Rule| R[Update Context] --> ALLOW
    style DENY fill:#ff6b6b,color:#fff
    style ALLOW fill:#51cf66,color:#fff
    style ASK1 fill:#ffd43b,color:#333
    style ASK2 fill:#ffd43b,color:#333
    style ASK3 fill:#ffd43b,color:#333
```
:::

:::{note}
Deny rules and dangerous-path checks are **non-bypassable** — they apply even in `BYPASS` mode.
:::

## Permission Mode

The `PermissionMode` enum (`io.agentscope.core.permission.PermissionMode`) supports the following modes:

| Mode | Behaviour | Use case |
|------|-----------|----------|
| `DEFAULT` | All operations require explicit rules or user confirmation | Safest default, recommended |
| `ACCEPT_EDITS` | Auto-allow file ops inside the working directory | Active development with the user present |
| `EXPLORE` | Read-only: allow reads, deny all writes and commands | Code exploration, planning |
| `BYPASS` | Allow everything (deny / ask rules still apply) | Fully trusted sandbox |
| `DONT_ASK` | Demote ASK to DENY | Unattended / scheduled runs |

Set the mode on the agent builder via `permissionContext(...)`:

::::{tab-set}
:::{tab-item} Initial config
```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;

PermissionContextState permCtx =
        PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("...")
                .model(model)
                .permissionContext(permCtx)
                .build();
```
:::
:::{tab-item} ACCEPT_EDITS with working dir
```java
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;

PermissionContextState permCtx =
        PermissionContextState.builder()
                .mode(PermissionMode.ACCEPT_EDITS)
                .addWorkingDirectory(
                        "/my/project",
                        new AdditionalWorkingDirectory("/my/project", "userSettings"))
                .build();
```
:::
::::

## Permission Rule

`PermissionRule` (a record) maps a tool plus a specific call pattern to one of three behaviours: `ALLOW`, `DENY`, `ASK`.

Each rule has the fields below. When the engine evaluates a rule, it calls the tool's `matchRule()` with the `ruleContent` and the actual input to decide whether the rule fires.

- **`toolName` · `String` · *required*** — The tool name the rule applies to: `todo_write` (built-in) or any custom tool name.

- **`ruleContent` · `String | null` · *required*** — Match pattern — semantics depend on the tool, interpreted by the tool's `matchRule()`. `null` means the rule matches every invocation of that tool.

- **`behavior` · `PermissionBehavior` · *required*** — `ALLOW`, `DENY`, `ASK`, or `PASSTHROUGH`

- **`source` · `String` · *required*** — Origin of the rule: `"userSettings"`, `"projectSettings"`, `"session"`, `"suggested"`, …

### Configuring rules

**At init time** — pass rules through `PermissionContextState.builder()`:

```java
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;

PermissionContextState permCtx =
        PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAllowRule(
                        "safe_read",
                        new PermissionRule(
                                "safe_read", null, PermissionBehavior.ALLOW, "userSettings"))
                .addAskRule(
                        "dangerous_delete",
                        new PermissionRule(
                                "dangerous_delete",
                                null,
                                PermissionBehavior.ASK,
                                "userSettings"))
                .addDenyRule(
                        "drop_table",
                        new PermissionRule(
                                "drop_table", null, PermissionBehavior.DENY, "userSettings"))
                .build();
```

**At runtime via suggested rules** — when the permission system returns ASK, it auto-generates suggested rules based on the current invocation. Pass the accepted rules in `ConfirmResult` and the agent will write them into the engine:

```java
import io.agentscope.core.event.ConfirmResult;

// ASK decisions carry suggestedRules on the ToolUseBlock.
// Accept them by attaching to the result:
ConfirmResult result =
        new ConfirmResult(
                /* confirmed = */ true,
                /* toolCall  = */ toolCall,
                /* rules     = */ toolCall.getSuggestedRules());
```

Runnable examples: `agentscope-examples/documentation/.../tool/PermissionContextExample.java`, `hitl/PermissionHITLExample.java`.

## Built-in checks

Every tool implements `checkPermissions(toolInput, context)` (on `ToolBase`) — a runtime check on the actual input that returns `Mono<PermissionDecision>`. These checks cannot be bypassed: they apply regardless of mode or rules.

`PermissionDecision` provides four static factories: `allow(message)` / `deny(message)` / `ask(message)` / `passthrough(message)`. Returning `PASSTHROUGH` means "I'm not deciding — let the engine evaluate rules and mode."

A custom tool can override `checkPermissions()` for its own logic:

```java
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolExecutionContext;
import java.util.Map;
import reactor.core.publisher.Mono;

public class MyTool extends ToolBase {

    public MyTool() {
        super(
                ToolBase.builder()
                        .name("MyTool")
                        .description("...")
                        .readOnly(false));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, ToolExecutionContext context) {
        Object target = toolInput.get("target");

        // Custom safety check: block production resources.
        if (target instanceof String s && s.startsWith("prod-")) {
            return Mono.just(
                    PermissionDecision.ask("Operation targets production resource: " + s));
        }

        // Return PASSTHROUGH to let the engine continue evaluating rules / mode.
        return Mono.just(PermissionDecision.passthrough("default"));
    }
}
```

### Dangerous-path protection

The `ToolBase` dangerous-path list is maintained in `ToolDangerousPathConstants`. A custom tool can append more paths via the `dangerousFiles` / `dangerousDirectories` attributes on `@Tool`. Once matched, the path triggers ASK even in `BYPASS` mode.

| Category | Examples |
|----------|----------|
| Shell config | `.bashrc`, `.zshrc`, `.bash_profile`, `.profile` |
| Git config | `.gitconfig`, `.gitmodules` |
| SSH | `.ssh/config`, `.ssh/authorized_keys`, `id_rsa`, `id_ed25519` |
| Credentials | `.env`, `.env.local`, `.npmrc`, `.pypirc`, `.aws/credentials` |
| Directories | `.git/`, `.ssh/`, `.aws/`, `.kube/` |

## Common recipes

The examples below show how to configure `permissionContext` for typical deployment scenarios. Each recipe combines a mode with a rule set tuned for one use case.

::::{tab-set}
:::{tab-item} Read-only exploration
```java
// EXPLORE mode: agent freely calls read-only tools; all writes are auto-denied.
PermissionContextState explore =
        PermissionContextState.builder()
                .mode(PermissionMode.EXPLORE)
                .build();

ReActAgent explorer =
        ReActAgent.builder()
                .name("explorer")
                .sysPrompt("...")
                .model(model)
                .permissionContext(explore)
                .build();
```
:::
:::{tab-item} Unattended automation
```java
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;

PermissionContextState ci =
        PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK)
                .addAllowRule(
                        "deploy",
                        new PermissionRule(
                                "deploy", "staging", PermissionBehavior.ALLOW, "project"))
                .addAllowRule(
                        "git_commit",
                        new PermissionRule(
                                "git_commit", null, PermissionBehavior.ALLOW, "project"))
                .build();

ReActAgent ciAgent =
        ReActAgent.builder()
                .name("ci_agent")
                .sysPrompt("...")
                .model(model)
                .permissionContext(ci)
                .build();
// Only explicitly allowed commands run; everything else is silently denied.
```
:::
:::{tab-item} Block dangerous commands
```java
PermissionContextState bypassWithDeny =
        PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                .addDenyRule(
                        "drop_table",
                        new PermissionRule(
                                "drop_table", null, PermissionBehavior.DENY, "userSettings"))
                .addDenyRule(
                        "force_push",
                        new PermissionRule(
                                "force_push", null, PermissionBehavior.DENY, "userSettings"))
                .build();
// Everything except the explicitly denied tools runs (deny rules can't be bypassed).
```
:::
::::
