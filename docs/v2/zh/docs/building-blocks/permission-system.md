---
title: "Permission System"
description: "精细控制 agent 可以执行哪些 tool、何时执行"
---

## 概述

Permission system（`io.agentscope.core.permission`）拦截 agent 的每一次工具调用，给出三种决策之一：**允许（ALLOW）** 执行、**拒绝（DENY）** 执行，或者**询问用户（ASK）** 确认。

它把静态配置与动态运行时分析组合起来。三个组件共同决定结果：

- **Rules** —— 针对每个 tool 与命令的显式 allow / deny / ask 模式，最高优先级。规则有两种来源：在 `PermissionContextState` 中静态预配置，或在 ASK 提示中由用户接受**建议规则**而动态加入。建议规则由本次工具调用自动生成 —— 一旦接受，将来相同的调用便会被自动处理，不再询问。
- **Mode** —— 配置阶段设定的全局静态策略；决定所有不命中任何规则的调用的默认行为（例如 `EXPLORE` 让 agent 进入只读；`DONT_ASK` 静默拒绝未命中的调用）。
- **Built-in Checks** —— 由 tool 自身在运行时基于真实输入做的动态分析（在 `ToolBase#checkPermissions` 中实现）。这些是运行时检查而非预配置模式，因此**不可绕过**，不受 mode 或 rules 覆盖。

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

:::{dropdown} 详细决策流程
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
Deny 规则与危险路径检查是**不可绕过的** —— 即使在 `BYPASS` 模式下也照常生效。
:::

## Permission Mode

`PermissionMode` 枚举（`io.agentscope.core.permission.PermissionMode`）支持以下模式，分别适配不同的部署场景：

| Mode | 行为 | 适用场景 |
|------|------|----------|
| `DEFAULT` | 所有操作都需要显式规则或用户确认 | 最安全，推荐默认值 |
| `ACCEPT_EDITS` | 自动放行工作目录内的文件操作 | 用户在场的活跃开发 |
| `EXPLORE` | 只读：放行读、拒绝所有写与命令 | 代码探索、规划 |
| `BYPASS` | 放行一切（deny / ask 规则仍生效） | 完全可信的沙箱 |
| `DONT_ASK` | 把所有 ASK 转为 DENY | 无人值守 / 计划任务 |

可以在创建 agent 时通过 `permissionContext(...)` 设置 mode：

::::{tab-set}
:::{tab-item} 初始化时配置
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
:::{tab-item} ACCEPT_EDITS 配合工作目录
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

`PermissionRule`（record）把某个 tool 与具体的调用模式映射到三种行为之一：`ALLOW`、`DENY`、`ASK`。

每条规则由下述字段组成。当权限引擎评估一条规则时，它会用 `ruleContent` 与实际调用入参调用该 tool 的 `matchRule()` 方法，判断规则是否命中。

- **`toolName` · `String` · *required*** — 规则适用的 tool 名：内置 `todo_write`，或任意自定义 tool 名。

- **`ruleContent` · `String | null` · *required*** — 匹配模式 —— 语义随 `toolName` 变化，由该 tool 的 `matchRule()` 方法解释。`null` 表示对该 tool 的所有调用均匹配。

- **`behavior` · `PermissionBehavior` · *required*** — `ALLOW`、`DENY`、`ASK` 或 `PASSTHROUGH`

- **`source` · `String` · *required*** — 规则来源：`"userSettings"`、`"projectSettings"`、`"session"`、`"suggested"` 等。

### 配置规则

**初始化时** —— 通过 `PermissionContextState.builder()` 把规则传入：

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

**运行时通过建议规则** —— 当权限系统返回 ASK 时，会基于本次调用自动生成建议规则。把已接受的规则附在 `ConfirmResult.acceptedRules` 中回传，agent 会自动写入引擎：

```java
import io.agentscope.core.event.ConfirmResult;

// ASK 决策中包含基于本次调用生成的 suggestedRules（位于 ToolUseBlock 上）。
// 接受建议时，把它放入结果即可：
ConfirmResult result =
        new ConfirmResult(
                /* confirmed = */ true,
                /* toolCall  = */ toolCall,
                /* rules     = */ toolCall.getSuggestedRules());
```

完整可运行示例：`agentscope-examples/documentation/.../tool/PermissionContextExample.java`、`hitl/PermissionHITLExample.java`。

## Built-in Checks

每个 tool 都实现了一个 `checkPermissions(toolInput, context)` 方法（位于 `ToolBase`），在运行时基于真实调用入参执行检查，返回 `Mono<PermissionDecision>`。这些检查不可绕过 —— 无论 mode 或 rules 是什么，它们都生效。

`PermissionDecision` 提供四个静态构造方法：`allow(message)` / `deny(message)` / `ask(message)` / `passthrough(message)`。返回 `PASSTHROUGH` 表示「我不强加判断，交给引擎按 rules / mode 评估」。

自定义 tool 可以重写 `checkPermissions()` 实现自己的检查逻辑：

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

        // 自定义安全检查：阻止操作生产资源
        if (target instanceof String s && s.startsWith("prod-")) {
            return Mono.just(
                    PermissionDecision.ask("Operation targets production resource: " + s));
        }

        // 返回 PASSTHROUGH 让引擎继续按 rules / mode 评估
        return Mono.just(PermissionDecision.passthrough("default"));
    }
}
```

### 危险路径保护

`ToolBase` 内置的危险路径列表通过 `ToolDangerousPathConstants` 维护，自定义 tool 可以在 `@Tool` 注解上追加 `dangerousFiles` / `dangerousDirectories` 把额外路径并入受保护集合。命中后即使在 `BYPASS` 模式下也会强制 ASK。

| 类别 | 默认受保护示例 |
|------|----------------|
| Shell 配置 | `.bashrc`、`.zshrc`、`.bash_profile`、`.profile` |
| Git 配置 | `.gitconfig`、`.gitmodules` |
| SSH | `.ssh/config`、`.ssh/authorized_keys`、`id_rsa`、`id_ed25519` |
| 凭证 | `.env`、`.env.local`、`.npmrc`、`.pypirc`、`.aws/credentials` |
| 目录 | `.git/`、`.ssh/`、`.aws/`、`.kube/` |

## 常见配方

下面的示例展示了如何为常见部署场景配置 `permissionContext`。每个配方把一种 mode 与一组规则结合，匹配特定的使用场景。

::::{tab-set}
:::{tab-item} 只读探索
```java
// EXPLORE 模式：agent 可以自由调用只读工具，所有写工具会被自动拒绝。
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
:::{tab-item} 无人值守自动化
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
// 只有显式放行的命令会执行；其余调用被静默拒绝
```
:::
:::{tab-item} 阻止危险命令
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
// 除显式拒绝的工具外，其余均放行（deny 规则不可绕过）
```
:::
::::
