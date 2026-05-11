# 沙箱（Sandbox）

[Filesystem](./filesystem.md) 说明了 agent 的「文件与命令」从哪来。当这些操作必须**与宿主进程隔离**、在**可替换的执行环境**（本地 Unix、Docker 等）里完成，并在多次 `call` 之间**恢复同一份工作区状态**时，应选用本文描述的 **沙箱模式**（`filesystem(SandboxFilesystemSpec)`）。

## 1. 沙箱解决什么问题

- **执行边界**：模型通过同一套 `AbstractFilesystem` / `ShellExecuteTool` 接口操作文件与命令，但**真实 IO 与进程**在沙箱客户端所管理的隔离环境里完成，适合不可完全信任用户输入、或需与生产宿主解耦的场景。
- **可恢复的工作单元**：与「单次 HTTP 请求」不同，多轮 `call` 应能接续同一逻辑工作区。`SandboxManager` 在每次 `call` 结束时**持久化沙箱侧状态**（通过 `SandboxStateStore`），下次 `acquire` 时按 `IsolationScope` 与 `sessionId`/`userId` 等键找回。
- **与 harness 工作区的关系**：宿主机上仍有 `WorkspaceManager` 根目录；沙箱内可见的内容由 `WorkspaceSpec` 与**工作区投影**等机制定义（将部分宿主路径在启动时同步/挂载到沙箱内）。

## 2. 在 Harness 中的装配

启用沙箱模式时，`HarnessAgent.Builder` 会：

1. 用 **`SandboxFilesystemSpec#toSandboxContext(hostWorkspaceRoot)`** 得到 **`SandboxContext`**（内含 `SandboxClient`、隔离范围、快照 spec、`WorkspaceSpec` 等），并同时把宿主侧需要投影进沙箱的目录（`AGENTS.md`、`skills/`、`subagents/`、`knowledge/`）装入一个 `WorkspaceProjectionEntry`（见 [§6 工作区投影](#6-工作区投影与-skills-同步)）。
2. 使用 **`SandboxBackedFilesystem`** 作为 agent 的 `AbstractFilesystem` 实现（对上层透明）。
3. 构造 **`SandboxManager(client, stateStore, agentId)`**；未在 **`SandboxFilesystemSpec#sandboxStateStore`** 上显式配置时，默认使用 **`SessionSandboxStateStore(effectiveSession, agentId)`**，将沙箱元数据与当前 `Session` 关联。
4. 注册 **`SandboxLifecycleHook(sandboxManager, filesystemProxy)`**（优先级 `50`）：在每次 `PreCall` 中 **acquire → `start()`**（含 4-分支工作区初始化，见 [§5 快照与 4-分支恢复](#5-快照与-4-分支恢复)），在 **`PostCall` / `Error`** 中 **`stop()`（持久快照）→ 持久化 state → release** 并清空代理上的活动会话。

只有后端实现 **`AbstractSandboxFilesystem`** 时，`HarnessAgent` 才会注册 **`ShellExecuteTool`**；沙箱模式下文件与 shell 命令都走沙箱内部，宿主机不受影响。

## 3. 隔离维度（`IsolationScope`）

`IsolationScope` 控制**沙箱状态的持久化键**（sandbox 模式）以及**共享存储的命名空间前缀**（store 模式，见 [Filesystem 模式一](./filesystem.md)）。两个模式共用同一个枚举，语义一致。

| 范围 | 持久化键来源 | 缺失时行为 | 典型场景 |
|------|------------|----------|---------|
| `SESSION`（默认） | `sessionKey.toIdentifier()` | 跳过状态查找，创建新沙箱 | 每个会话有独立的沙箱/记忆；对话隔离 |
| `USER` | `RuntimeContext.userId` | 警告并降级到新建 | 同一用户跨会话共享工作区或记忆（含分布式） |
| `AGENT` | agent 名称（构建时固定） | — | 单个 agent 的所有用户和会话共享同一工作区 |
| `GLOBAL` | 固定值 `__global__` | — | 一个 store 内所有 agent/用户/会话全局共享 |

### 3.1 SESSION — 对话隔离（默认）

每条对话独立沙箱，互不影响。适合多用户 SaaS，每个会话的临时工作文件、已安装的依赖互相隔离。

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("code-agent")
    .model(model)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .snapshotSpec(new OssSnapshotSpec(...)))
    // isolationScope 默认即 SESSION，此行可省略
    .filesystem(dockerSpec.isolationScope(IsolationScope.SESSION))
    .build();

// 每次 call 传入不同 sessionId → 独立的沙箱
agent.call(msgs, RuntimeContext.builder().sessionId("user1-session1").build()).block();
agent.call(msgs, RuntimeContext.builder().sessionId("user1-session2").build()).block();
```

### 3.2 USER — 用户级共享（分布式记忆的推荐方式）

**最常见的分布式场景**：多 Pod/多进程对同一用户的多个会话并行服务，但用户的长期记忆（`MEMORY.md`、`memory/`）要在所有副本间保持一致。

**Sandbox 模式 + USER**：不同会话（不同 Pod）在对话结束后都会向同一个 state slot（键 = `userId`）写入最新的快照引用。下次任意副本处理同一用户时，都能从该快照恢复出同一个工作区。注意这是**顺序复用**而非并发共享：并发请求各自拿到独立的容器运行，但在 `stop()` 时都会更新同一 state slot，最后写入的为准。

**Remote 模式 + USER**（无沙箱时的等价方案）：`RemoteFilesystemSpec` 用 `userId` 作为 KV 命名空间前缀，所有路由到 `MEMORY.md`、`memory/` 等的读写都落在同一 store key 下，从而实现分布式副本之间的记忆共享，而无需快照。

```java
// 沙箱 + USER 隔离：同一用户跨 Pod 共享快照
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .snapshotSpec(new OssSnapshotSpec(...))
        .isolationScope(IsolationScope.USER))
    .sandboxDistributed(SandboxDistributedOptions.oss(redisSession, ossSnapshotSpec))
    .build();

RuntimeContext ctx = RuntimeContext.builder()
    .userId("alice")       // 相同 userId → 相同 state slot → 可恢复同一工作区
    .sessionId("session-xyz")
    .build();
agent.call(msgs, ctx).block();
```

```java
// Remote 模式 + USER 隔离：轻量级分布式记忆共享（无沙箱）
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .filesystem(new RemoteFilesystemSpec(redisStore)
        .isolationScope(IsolationScope.USER))
    .build();
// 同一 userId 的所有副本共享 MEMORY.md / memory/ 目录下的记忆
```

### 3.3 AGENT — Agent 级共享

同一个 agent（按名称）的所有用户和会话共享工作区快照或存储命名空间。适合「公共知识库型」agent：全局单一工作区，写入由调用顺序决定，适合工具型、只读型或管理员场景。

### 3.4 GLOBAL — 全局共享

一个 store/workspace 实例内最大范围的共享，谨慎使用。

## 4. 自定义沙箱实例与生命周期管理

默认情况下，`SandboxManager` 全权负责沙箱的 create / start / stop / shutdown（**self-managed**）。当你需要**复用已有容器**、**在多个 agent 之间共享一个沙箱**，或**自己管理容器生命周期**时，可通过两种方式将沙箱控制权交还给调用方。

### 4.1 传入已有 `Sandbox` 实例（user-managed，最高优先级）

在每次 `call` 时，通过 `RuntimeContext` 中的 `SandboxContext` 带入一个**已经启动的** `Sandbox` 对象：

```java
// 提前创建并启动沙箱（容器生命周期由调用方管理）
Sandbox mySandbox = dockerClient.create(workspaceSpec, snapshotSpec, options);
mySandbox.start();

// 每次 call 时注入该实例
SandboxContext callCtx = SandboxContext.builder()
    .client(dockerClient)         // 同 agent 构建时的 client
    .externalSandbox(mySandbox)   // ← 明确告知 Manager：这是 user-managed
    .build();

RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("my-session")
    .sandboxContext(callCtx)      // 覆盖构建时的 defaultSandboxContext
    .build();

agent.call(msgs, ctx).block();
// SandboxLifecycleHook 会调用 mySandbox.stop()（持久快照）
// 但 不 会 调用 mySandbox.shutdown()，容器依然运行
```

**行为规则**（`SandboxManager.acquire` 的 4 级优先级）：

| 优先级 | 条件 | 行为 |
|--------|------|------|
| 1（最高） | `SandboxContext.externalSandbox != null` | 直接使用，标记 user-managed；`PostCall` 仅调 `stop()`，不 `shutdown()` |
| 2 | `SandboxContext.externalSandboxState != null` | 从指定 state 恢复，self-managed |
| 3 | `SandboxStateStore` 中有持久化的 state | 按 `IsolationScope` 键恢复，self-managed |
| 4（默认） | 以上均无 | 创建新沙箱，self-managed |

### 4.2 传入序列化状态（精确恢复特定快照）

若你已持有某次 `call` 后保存的 `SandboxState` 序列化串，可绕过 `SandboxStateStore` 的自动查找，直接指定要恢复的状态：

```java
// 从外部获取之前序列化的 state（例如从数据库或请求参数中读取）
String savedStateJson = db.load("checkpoint-2026-04-28");
SandboxState savedState = dockerClient.deserializeState(savedStateJson);

SandboxContext callCtx = SandboxContext.builder()
    .client(dockerClient)
    .externalSandboxState(savedState)   // ← 指定 state，SDK 负责 resume + 管理生命周期
    .build();

RuntimeContext ctx = RuntimeContext.builder()
    .sandboxContext(callCtx)
    .build();

agent.call(msgs, ctx).block();
```

### 4.3 多 Agent 共享同一沙箱

```java
// 主 agent 完成一个 call 后，把沙箱传给下一个 agent 继续使用
Sandbox sharedSandbox = ...;  // 已 start()

agent1.call(msgs1, RuntimeContext.builder()
    .sandboxContext(SandboxContext.builder().externalSandbox(sharedSandbox).client(client).build())
    .build()).block();

agent2.call(msgs2, RuntimeContext.builder()
    .sandboxContext(SandboxContext.builder().externalSandbox(sharedSandbox).client(client).build())
    .build()).block();

// 所有 agent 用完后手动 shutdown
sharedSandbox.shutdown();
```

## 5. 快照与 4-分支恢复

`Sandbox.start()` 按 **4 个分支**决定如何初始化工作区，保证在各种「容器是否还在、快照是否可用」的组合下都能正确恢复：

```
Branch A: workspaceRootReady=true  &  容器内目录仍存在   → 只重新应用 ephemeral 条目（最快，热启动）
Branch B: workspaceRootReady=true  &  容器内目录已丢失   → 从快照还原 + 重新应用 ephemeral 条目
Branch C: workspaceRootReady=false &  快照可用           → 从快照还原 + 重新应用所有条目
Branch D: workspaceRootReady=false &  无可用快照         → 从 WorkspaceSpec 全量初始化（冷启动）
```

`Sandbox.stop()` 执行时若 `SandboxSnapshotSpec` 启用了持久化，则将工作区打成 tar 并存入快照后端（OSS、Redis、本地文件等），同时把 `workspaceRootReady` 置 true。这个 tar 就是下次恢复时供 Branch B/C 使用的**归档**。

**`WorkspaceEntry.ephemeral` 标志**：`WorkspaceSpec` 中的每个条目都可以标记为 ephemeral（每次启动都重新写入）或非 ephemeral（进快照一同保存，只在冷启动时写入）。`skills/`、`AGENTS.md` 等宿主侧随时可能更新的文件，以 `WorkspaceProjectionEntry` 的方式处理（下节），而不是 ephemeral flag。

**快照 spec 可选类型**：

| Spec | 存储位置 |
|------|---------|
| `NoopSnapshotSpec`（默认） | 不持久化；容器重建后从 WorkspaceSpec 冷启动 |
| `LocalSnapshotSpec` | 宿主机本地文件（适合单机长期运行） |
| `OssSnapshotSpec` | OSS / S3 兼容存储（适合多副本） |
| `RedisSnapshotSpec` | Redis（适合低延迟、小工作区） |

## 6. 工作区投影与 Skills 同步

**工作区投影**（`WorkspaceProjectionEntry`）是 harness 将宿主机工作区里的特定目录/文件在**每次沙箱启动时**同步进沙箱的机制，是 Skills 等能力在沙箱内运行的基础。

### 6.1 投影范围

`SandboxFilesystemSpec` 构建 `SandboxContext` 时，默认把以下宿主路径打包进投影：

```
AGENTS.md       ← agent 身份与指令
skills/         ← SkillBox 里所有 Skill 的目录（含 SKILL.md 和脚本文件）
subagents/      ← 子 agent 规格文件
knowledge/      ← 领域知识文件
```

可通过 `SandboxFilesystemSpec#workspaceProjectionRoots(List<String>)` 自定义要投影的根路径，或通过 `workspaceProjectionEnabled(false)` 完全关闭。

### 6.2 投影如何工作

`WorkspaceProjectionApplier` 在 `Sandbox.start()` 末尾执行：

1. 遍历所有 `WorkspaceProjectionEntry`，收集宿主侧的文件集合，按路径排序后计算 **SHA-256 内容哈希**。
2. 将这批文件打包成 tar，通过 `Sandbox.hydrateWorkspace(archive)` 解压到沙箱工作区内对应路径。
3. 把本次哈希存入 `SandboxState.workspaceProjectionHash`；下次启动时若哈希不变，**跳过投影**（避免重复传输）。

这意味着：宿主机上 `skills/` 的内容更新后，下次沙箱 start 时哈希变化，新版文件自动同步进去；沙箱内对 skill 文件的修改不会反向同步回宿主机。

### 6.3 Skills 在沙箱内怎么执行

Harness 的 `SkillBox` 机制把 `workspace/skills/<skill-name>/SKILL.md` 里的说明注入 agent 的 system prompt；model 理解「需要这个 skill」后通过 `ShellExecuteTool` 执行 skill 目录下的脚本或命令。在沙箱模式下，这一切都在沙箱内进行：

```
宿主机 workspace/skills/pytest/
│── SKILL.md          # 描述：如何运行 pytest
└── run_tests.sh      # 实际脚本

         ▼ 投影（每次启动时）

沙箱内 /workspace/skills/pytest/
│── SKILL.md
└── run_tests.sh

agent 思考后调用 shell_execute:
  "bash /workspace/skills/pytest/run_tests.sh tests/"
          ↓
   ExecResult(exitCode=0, stdout="5 passed")
```

**优点**：脚本运行在隔离容器内，pip install、apt-get、rm -rf 等操作只影响沙箱工作区，宿主机无感。沙箱被 snapshot 后，已安装的依赖也会随工作区一起被归档，下次恢复时直接可用（Branch A/B/C），无需重新安装。

### 6.4 Shell 命令与脚本的状态持久化

`ShellExecuteTool` 调用 `AbstractSandboxFilesystem.execute(cmd, timeout)` → `Sandbox.exec(cmd, timeout)`，在沙箱内执行命令。命令对文件系统的所有更改（新建文件、安装包、写日志等）都保留在沙箱的 overlay/容器内。`stop()` 时这些状态随 tar 快照持久化，下次 `start()` 恢复。

因此，跨 `call` 的**状态是完整保留的**：

```
call 1: shell_execute("pip install pandas")   → pandas 装进沙箱
call 2: shell_execute("python analyze.py")    → 直接可用，无需重装
call 3: shell_execute("cat results.csv")      → 读 call 2 产生的文件
```

## 7. 状态：`SandboxStateStore` 与 `Session`

- **`SandboxStateStore`**：抽象「与某次隔离键绑定的沙箱元数据（sessionId + 快照引用）」的持久化。便于替换为自定义实现；在 **`SandboxFilesystemSpec#sandboxStateStore`** 上配置（未设置则走默认）。
- **默认 `SessionSandboxStateStore`**：依赖构建时选定的 `Session`（与 `SessionPersistenceHook` 等共用的**会话抽象**；若你使用 Redis 等分布式 `Session`，沙箱元数据可随之跨进程可见）。
- **`WorkspaceSession`** 仍负责**工作区布局下的 per-session 配置**；**不要**将 `WorkspaceSession` 的 JSON 与「沙箱 state JSON」混为同一套职责——沙箱的 resume 数据以 **`SandboxStateStore`** 为准。

## 8. 分布式与 `sandboxDistributed`

当多副本或无状态 worker 要共享**同一条逻辑会话**的沙箱恢复能力时，需要：

- **分布式 `Session`**（如 `RedisSession`），而不仅是默认的 `WorkspaceSession` 文件后端；以及
- 非 no-op 的 **`SandboxSnapshotSpec`**（将工作区打成可再拉取的归档），在「必须分布式」的校验下才会通过。

`HarnessAgent.Builder#sandboxDistributed(SandboxDistributedOptions)` 可统一下发：

- 覆盖 **`snapshotSpec`**（若提供）；**`IsolationScope` 只在 `SandboxFilesystemSpec` 上配置**，不在此重复；
- 在选项中**显式指定**用于沙箱的 `Session`（若与主 `session` 不同）；
- 使用 `SandboxDistributedOptions#oss` / `#redis` 等辅助构造常见组合（见类 JavaDoc）。

若 `requireDistributed` 为 true 而当前 `effectiveSession` 仍是 `WorkspaceSession` 或快照为 no-op，构建会 **fail-fast**。

## 9. 与三种 Filesystem 模式怎么选

沙箱是三种**声明式**配置之一。完整对比见 [Filesystem](./filesystem.md#三种声明式模式)；此处只给决策要点：

| 你更需要 | 推荐模式 |
|----------|----------|
| 多实例共享 `MEMORY.md`、会话日志等到 KV，**不要**在宿主跑 shell | `RemoteFilesystemSpec`（见 [Filesystem — 模式一](./filesystem.md)） |
| 单进程/本机、信任 shell、**不要**另起沙箱 | `LocalFilesystemSpec` 或默认本机 + shell（见 [Filesystem — 模式三](./filesystem.md)） |
| **隔离执行**、命令与文件落沙箱、**长会话恢复**、可选**快照 + 集群** | **`SandboxFilesystemSpec`（本文）+ 可选 `sandboxDistributed`** |

## 10. 子 Agent

已启用 `SubagentsHook` 时，若主 agent 在沙箱模式下构建，**子 agent 的 filesystem 会复用**同一 `SandboxBackedFilesystem` 的会话绑定策略（以当前实现为准，便于在同一次编排树内共享环境）。子 agent 本身仍是独立 `ReActAgent`；隔离边界与主 agent 的沙箱 spec 一致。

## 11. 延伸阅读

- [Filesystem](./filesystem.md) — 类层次、三种模式、`abstractFilesystem` 逃生口
- [工具](./tool.md) — `FilesystemTool`、`ShellExecuteTool` 入参
- [会话](./session.md) — `Session` 与 `WorkspaceSession`
- [架构](./architecture.md) — Hook 协作与时序
