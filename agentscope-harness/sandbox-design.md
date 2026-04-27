# HarnessAgent 中实现一套 Sandbox 管理系统

## 总体目标
我们需要深度参考 OpenAi Agents SDK，为 HarnessAgent 提供对应对应的 Sandbox 实现，sandbox可用来执行 subagent 和 tool。我们需要一个 SandboxManager 来管理 sandbox 的生命周期，在 agent 运行过程中，但需要 sandbox 的时候，通过 SandboxManager 来实现获取（按需创建）sandbox实例，并注入给 AbstractFilesystem 使用。

每次新创建的 Sandbox 实例本身是无状态的，但是我们需要帮助它恢复到之前的状态，这些状态包括，对应到 OpenAi Agents SDK 中的 Manifest、workspace 等，为了恢复这些状态，可能需要依赖 session 中存储的sandbox state、snapshot等。

Sandbox 实例通过后端的Docker、Unix等实际实现来做实际的底层存储实现，这类似 OpenAi Agents SDK 中有 BaseSandboxSession、DockerSandboxSession等的基类和扩展实现等策略。

同时，为了能让用户使用 HarnessAgent 时能使用这套 sandbox 体系，我们需要开放 API 给用户进行配置。包括通过 HarnessAgent Builder 来设置使用哪个 Sandbox Client、Client Options、Snapshot实现，使用 RuntimeContext 动态的传入 client、sandbox实例、sandbox state、snapshot实例等。

## 部分详细设计
### Sandbox 实例管理与生命周期
实现一个 SandboxManager 类，用来管理 sandbox 实例，它根据当前Agent在运行过程中传入的 SandboxContext（可以设计从RuntimeContext中读取）来尝试获取、创建、恢复 sandbox 实例（比如根据SandboxContext中的state来查询和检查可用实例），不论是新建的还是计划恢复的实例，都应该恢复或初始化实例的工作空间（比如通过snapshot）。

在使用 HarnessAgent 时，有两种方式管理sandbox实例，一种是通过SandboxManager创建的，一种是用户直接传入的（比如通过RuntimeContext），对应 OpenAi Agents SDK 中的 SDK owned sandbox 和 developer owned sandbox 模式。

### 关于 Sandbox 实例获取与创建的时机

我现在的想法是放到Hook中，根据 HarnessAgent、RuntimeContext中的配置来决定是不是启用sandbox。增加一个Hook，它主要处理PreCallEvent事件，调用SandboxManager来读取（按需创建）Sandbox实例，并把sandbox实例注入给需要的abstractFilesystem（如果是SandboxFilesystem类型的话）。最终所有在HarnessAgent中与RuntimeContext中的关于sandbox的配置都汇总到 RuntimeContext中，调用SandboxManager管理sandbox时根据RuntimeContext中读取到的sandbox配置来驱动组织，包括找实例、恢复workspace状态等。

### Sandbox 内部状态
Sandbox 实例本身是无状态的，一个新的实例创建出来后需要进行状态初始化，这取决于本地调用是要使用全新的实例还是准备通过resume恢复之前的实例。
如果是使用全新实例，那么直接参考 OpenAi Agents SDK 中的新实例物化过程进行初始化；如果是resume恢复，那么需要根据传入的snapshot等进行恢复，同样需要参考OpenAi Agents对应实现设计。

### Sandbox 基类与拓展实现
关于 Sandbox 类与实现的设计，需要深度参考 BaseSandboxSession、DockerSandboxSession 等基类和扩展实现。

Sandbox 内部通过 SandboxClient 来对接 Docker、Unix 等实际的后端实现。

### 用户 API
总的来说，我们是需要在HarnessAgent中增加API，配合HarnessAgent中已有的session等机制来确保sandbox整体能够正常工作和使用。

OpenAi Agents SDK 中的 RunState应该对等到ReActAgent中的session状态机制，我们可能需要在session中增加_sandbox部分拓展，支持SandboxSessionState等的存储。RunConfig中大部分参数应该是通过HarnessAgent Builder来配置，部分通过RuntimeContext来配置，比如重点需要考虑的包括SandboxClient、SandboxConfigOptions等。

---

## Sandbox 隔离与共享策略（Isolation Scope）

### 背景

默认情况下，Sandbox 实例通过 `sessionKey` 隔离，即不同 session 之间不共享同一个 Sandbox 状态。但在实际业务中，我们希望能够灵活控制复用粒度：

- 同一用户的多个 session 复用同一个 Sandbox（用户粒度共享）
- 同一个 Agent 的所有调用复用同一个 Sandbox（Agent 粒度共享）
- 全局唯一一个 Sandbox（全局共享）

### 实现方式

共享语义是"**按 scope key 持久化状态 → 下次调用恢复**"，即顺序复用（sequential reuse），而非进程内运行实例的并发共享。

通过 `RuntimeContext.sandboxContext(...)` 传入的 `SandboxContext` 上设置 `isolationScope` 来选择 scope：

```java
RuntimeContext ctx = RuntimeContext.builder()
    .userId("user-42")
    .sandboxContext(
        SandboxContext.builder()
            .isolationScope(SandboxIsolationScope.USER)   // 用户粒度共享
            .build())
    .build();
agent.call(message, ctx);
```

### 四种 Scope

| Scope     | 复用粒度                           | 所需 RuntimeContext 字段 | 缺失时行为                          |
|-----------|------------------------------------|--------------------------|-------------------------------------|
| `SESSION` | 每个 `sessionKey`（默认）          | `sessionKey`             | 无 key → 直接新建                   |
| `USER`    | 同一 `userId` 的所有 session       | `userId`                 | userId 为空 → WARN 日志，直接新建   |
| `AGENT`   | 同一 Agent（按 agent name）        | 无需（build 时固定）     | 始终有效                            |
| `GLOBAL`  | 同一 workspace 内全局唯一          | 无需                     | 始终有效                            |

### 磁盘布局

```
<workspace>/
├── agents/<agentId>/
│   ├── context/<sessionId>/_sandbox.json        ← SESSION scope（向下兼容原有路径）
│   └── sandboxes/
│       ├── user/<safe(userId)>.json             ← USER scope
│       └── agent.json                           ← AGENT scope
└── sandboxes/
    └── global.json                              ← GLOBAL scope
```

文件名中包含非安全字符（非 `[a-zA-Z0-9_\-.]`）的值会用 Base64url 编码，与 `WorkspaceSession` 的策略保持一致。

### 并发注意事项

这是顺序复用模型，**不是**进程内活跃实例的并发共享：

- 相同 scope key 的并发调用，每次调用各自获取/创建独立的 Sandbox 实例
- `persistState` 采用最后写入覆盖（last-writer-wins）语义
- 需要并发共享同一个运行中实例的场景，属于未来的进阶功能（live instance registry）

### 关键类

| 类 / 接口 | 职责 |
|-----------|------|
| `SandboxIsolationScope` | 枚举：`SESSION` / `USER` / `AGENT` / `GLOBAL` |
| `SandboxIsolationKey` | `(scope, value)` 不可变值类型；`resolve()` 从 RuntimeContext 计算 key |
| `SandboxStateStore` | 接口：按 key 读写删除 sandbox state JSON |
| `WorkspaceSandboxStateStore` | 文件系统实现；SESSION scope 委托给 `WorkspaceSession` 以保持向下兼容 |
| `SandboxContext.isolationScope` | 每次 call 通过 `RuntimeContext.sandboxContext(...)` 传入 |
