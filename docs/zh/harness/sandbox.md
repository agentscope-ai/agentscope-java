# 沙箱（Sandbox）

[Filesystem](./filesystem.md) 说明了 agent 的「文件与命令」从哪来。当这些操作必须**与宿主进程隔离**、在**可替换的执行环境**（本地 Unix、Docker 等）里完成，并在多次 `call` 之间**恢复同一份工作区状态**时，应选用本文描述的 **沙箱模式**（`filesystem(SandboxFilesystemSpec)`）。

## 1. 沙箱解决什么问题

- **执行边界**：模型通过同一套 `AbstractFilesystem` / `ShellExecuteTool` 接口操作文件与命令，但**真实 IO 与进程**在沙箱客户端所管理的隔离环境里完成，适合不可完全信任用户输入、或需与生产宿主解耦的场景。
- **可恢复的工作单元**：与「单次 HTTP 请求」不同，多轮 `call` 应能接续同一逻辑工作区。`SandboxManager` 在每次 `call` 结束时**持久化沙箱侧状态**（通过 `SandboxStateStore`），下次 `acquire` 时按 `IsolationScope` 与 `sessionId`/`userId` 等键找回。
- **与 harness 工作区的关系**：宿主机上仍有 `WorkspaceManager` 根目录；沙箱内可见的内容由 `WorkspaceSpec` 与**工作区投影**等机制定义（将部分宿主路径在启动时同步/挂载到沙箱内）。

## 2. 在 Harness 中的装配

启用沙箱模式时，`HarnessAgent.Builder` 会：

1. 用 **`SandboxFilesystemSpec#toSandboxContext(hostWorkspaceRoot)`** 得到 **`SandboxContext`**（内含 `SandboxClient`、隔离范围、快照 spec、`WorkspaceSpec` 等），并同时把宿主侧需要投影进沙箱的目录（`AGENTS.md`、`skills/`、`subagents/`、`knowledge/`）装入一个 `WorkspaceProjectionEntry`（见 [§7 工作区投影](#7-工作区投影与-skills-同步)）。
2. 使用 **`SandboxBackedFilesystem`** 作为 agent 的 `AbstractFilesystem` 实现（对上层透明）。
3. 构造 **`SandboxManager(client, stateStore, agentId)`**；未在 **`SandboxFilesystemSpec#sandboxStateStore`** 上显式配置时，默认使用 **`SessionSandboxStateStore(effectiveSession, agentId)`**，将沙箱元数据与当前 `Session` 关联。
4. 注册 **`SandboxLifecycleHook(sandboxManager, filesystemProxy)`**（优先级 `50`）：在每次 `PreCall` 中 **acquire → `start()`**（含 4-分支工作区初始化，见 [§6 快照与 4-分支恢复](#6-快照与-4-分支恢复)），在 **`PostCall` / `Error`** 中 **`stop()`（持久快照）→ 持久化 state → release** 并清空代理上的活动会话。

只有后端实现 **`AbstractSandboxFilesystem`** 时，`HarnessAgent` 才会注册 **`ShellExecuteTool`**；沙箱模式下文件与 shell 命令都走沙箱内部，宿主机不受影响。

自定义 **Docker 以外的隔离后端**（实现 `SandboxClient`、`SandboxState`、`SandboxFilesystemSpec` 等）的完整步骤与自检清单见 **[§5 扩展自己的沙箱执行环境](#5-扩展自己的沙箱执行环境)**。

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

**Sandbox 模式 + USER**：不同会话（不同 Pod）在对话结束后都会向同一个 state slot（键 = `userId`）写入最新的快照引用。下次任意副本处理同一用户时，都能从该快照恢复出同一个工作区。注意这是**顺序复用**而非并发共享：并发请求各自拿到独立的容器运行，但在 `stop()` 时都会更新同一 state slot，最后写入的为准。`AGENT` / `GLOBAL` 范围如需强互斥，请参见 [§9 并发控制](#9-并发控制sandboxexecutionguard)。

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

## 5. 扩展自己的沙箱执行环境

当你的应用需要 **Docker 以外的隔离后端**（自建远端执行器、商用沙箱 API、本地 Mock 等）时，**无需修改 harness 源码**：实现下列契约类型，再通过 **`HarnessAgent.Builder#filesystem(SandboxFilesystemSpec)`** 接入即可。整体装配仍遵循 [§2](#2-在-harness-中的装配)：`SandboxContext` → `SandboxBackedFilesystem` → `SandboxManager` → `SandboxLifecycleHook`。

### 5.1 扩展点一览

| 扩展点 | 职责 | 与框架的契约 |
|--------|------|----------------|
| **`SandboxClient` + `SandboxClientOptions`** | 在隔离环境里 **create / resume**、**序列化/反序列化** 沙箱状态 | `create` 接收 `WorkspaceSpec`、`SandboxSnapshotSpec`（快照与「跑在哪」**正交**，见 [§6](#6-快照与-4-分支恢复)）与你的 options；`resume` / `deserializeState` 必须返回能与 `SandboxManager` 协作的 `Sandbox`。 |
| **`SandboxState` 子类** | 跨 `call` 需要持久化的后端专有字段（资源 id、镜像、内网路径等） | 基类仅保留 **`@JsonTypeInfo(property = "type")`**，**不用 `@JsonSubTypes` 在父类列死子类**。官方 Docker 在 **`HarnessSandboxJacksonModule`** 中注册 `docker` → `DockerSandboxState`；你的子类须在参与反序列化的 **`ObjectMapper`** 上 **`registerSubtypes(NamedType)`**（或等价 `SimpleModule`）。 |
| **`Sandbox` 实现** | `start` / `stop` / `shutdown`、`exec`、与工作区/快照协作 | 通常继承 **`AbstractBaseSandbox`**，复用 4-分支恢复与快照钩子；由你对接真实进程或 API。 |
| **`SandboxFilesystemSpec` 子类** | 把 client、options、默认 `WorkspaceSpec` / `SandboxSnapshotSpec` **接到 Harness** | 实现 **`createClient()`**、**`clientOptions()`**、**`snapshotSpec()`**、**`workspaceSpec()`**；可链式配置 **`isolationScope`**、**`sandboxStateStore`**、**`executionGuard`**、投影等（能力与 **`DockerFilesystemSpec`** 对齐）。 |

### 5.2 实现 `SandboxClient` 与 options

1. 定义 **`MySandboxClientOptions extends SandboxClientOptions`**：`**getType()**` 返回稳定字符串（如 `acme`），与持久化/配置里的 **`type`** 一致。若需从 YAML/JSON **反序列化 options**，要为 `SandboxClientOptions` 多态增加 Jackson 注册（可参考框架内 **`DockerSandboxClientOptions`** 与基类上的 **`@JsonTypeInfo`**）；仅 Java 代码配置时可跳过。  
2. 实现 **`SandboxClient<MySandboxClientOptions>`**：在 **`create`** 中构造**尚未 `start()`** 的 `Sandbox`；**`serializeState` / `deserializeState`** 与 **`SandboxState`** 子类字段一致；**`delete`** 若无额外资源可为 no-op。  
3. 若 **`deserializeState`** 使用 **`objectMapper.readValue(json, SandboxState.class)`**，该 **`ObjectMapper` 必须** 注册 **`HarnessSandboxJacksonModule`**（内置 `docker` 等官方 **`NamedType`**）以及你的 **`NamedType(MySandboxState.class, "acme")`**。**无参 `new DockerSandboxClient()`** 已自动注册 **`HarnessSandboxJacksonModule`**；**`new DockerSandboxClient(customMapper)`** 时需自行 **`registerModule(new HarnessSandboxJacksonModule())`** 并 **`registerSubtypes`**，否则读回持久化 state 会失败。

### 5.3 `SandboxState` 与 Jackson

- **设计意图**：子类型表由 **Module / `registerSubtypes`** 在运行时提供，便于同一应用或下游 jar **扩展 state**，而不改 **`SandboxState.java`**。  
- **本仓库**：在 **`io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule`** 中为新的官方后端追加 **`registerSubtypes(new NamedType(XxxSandboxState.class, "xxx"))`**。  
- **应用私有子类**：对持有沙箱 JSON 的 **`ObjectMapper`** 调用 **`mapper.registerSubtypes(new NamedType(MySandboxState.class, "acme"))`**，并保证 **`SandboxManager` / `SandboxStateStore`** 所走路径与 **`SandboxClient`** 使用**同一套** mapper 配置。

### 5.4 实现 `SandboxFilesystemSpec`

- **`createClient()`**：返回你的 **`SandboxClient`**（或由 **`options.createClient()`** 创建，与 **`DockerFilesystemSpec`** 相同模式）。  
- **`clientOptions()`**：返回可变配置对象。  
- **`snapshotSpec()`** / **`workspaceSpec()`**：可提供默认 **`NoopSnapshotSpec`** 与 **`new WorkspaceSpec()`**；调用方仍可用 **`SandboxFilesystemSpec#snapshotSpec(...)`** 在构建 agent 前覆盖。

**参考实现**：仓库中的 **`InMemorySandboxFilesystemSpec`**（`agentscope-harness` 测试 support、`harness-example-sandbox` 示例工程）用临时目录模拟沙箱、不依赖 Docker，适合作为最小骨架拷贝改造。

### 5.5 启用

与 Docker 相同，在 **`HarnessAgent.builder()`** 上传入你的 spec：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("my-agent")
    .model(model)
    .filesystem(new MySandboxFilesystemSpec()
        .isolationScope(IsolationScope.SESSION))
    .build();
```

需要分布式 **`Session`** 或 OSS/Redis 快照时，继续按需配置 **`sandboxDistributed(...)`**（见 [§10](#10-分布式与-sandboxdistributed)）；**快照 spec 与隔离执行后端独立选择**，在 **`SandboxFilesystemSpec`** 上分别指定即可。

### 5.6 自检清单

- [ ] **`Sandbox#getState()`** 写出的 JSON 能被 **`SandboxClient#deserializeState`** 无损读回。  
- [ ] **`SandboxClientOptions#getType()`** 与 **`SandboxState` JSON 的 `type`** 在全局不冲突。  
- [ ] 所有会对 **`SandboxState`** 做 **`readValue(..., SandboxState.class)`** 的 **`ObjectMapper`** 均已注册对应 **`NamedType`**（含 **`HarnessSandboxJacksonModule`** 若需读 Docker 兼容数据）。  
- [ ] 若走 **`AbstractSandboxFilesystem`**，**`ShellExecuteTool`** 与文件 API 已路由到你的 **`Sandbox#exec`** 与沙箱内路径约定。

### 5.7 可选沙箱后端：Kubernetes / Daytona / E2B（`agentscope-harness` 子包）

上述三类远端沙箱实现位于 **`agentscope-harness`** 的 **`io.agentscope.harness.agent.sandbox.impl.*`** 子包中（与 **`io.agentscope.harness.agent.sandbox.impl.docker`** 并列），同一 artifact 内即可选用；**fabric8**、**protobuf-java** 等依赖已声明在 **`agentscope-harness`** 的 `pom.xml` 中。

| Java 包 | 说明 |
|---------|------|
| **`io.agentscope.harness.agent.sandbox.impl.kubernetes`** | 以 Pod 为远端主机：fabric8 Exec + 容器内 `tar`；**`KubernetesFilesystemSpec`** / **`KubernetesSandboxClient`** / **`KubernetesHarnessSandboxJacksonModule`**（`NamedType`：`kubernetes`）。支持顶层 **`BindMountEntry`**（节点 **HostPath** + `volumeMount`），见 [§5.8](#58-工作区-bind-mountbindmountentry)。 |
| **`io.agentscope.harness.agent.sandbox.impl.daytona`** | Daytona HTTP API：**`DaytonaFilesystemSpec`**、**`DaytonaHarnessSandboxJacksonModule`**（`daytona`）、超时与有限重试。**不**应用宿主 bind mount；含 `bind_mount` 时启动会 **WARN**。 |
| **`io.agentscope.harness.agent.sandbox.impl.e2b`** | E2B：`https://api.e2b.app` 生命周期 + envd **`process.Process/Start`**（Connect+protobuf）；**`E2bFilesystemSpec`**、**`E2bHarnessSandboxJacksonModule`**（`e2b`）；**`E2bPersistenceMode#TAR`** 与 **`NATIVE_SNAPSHOT`**（平台快照 API + **`E2bSnapshotRefs`** magic 前缀）。**不**应用宿主 bind mount；含 `bind_mount` 时启动会 **WARN**；TAR 快照仍会对 bind 路径做 **`tar --exclude`**（与 Docker/K8s 一致）。 |

**Jackson 组装**：对持有沙箱 state JSON 的 **`ObjectMapper`** 至少注册 **`HarnessSandboxJacksonModule`**，再按需注册各后端的 **`SimpleModule`**（例如 **`new KubernetesHarnessSandboxJacksonModule()`**），与 [§5.3](#53-sandboxstate-与-jackson) 一致。**不要**在核心 **`SandboxClientOptions`** 上为可选后端增加 **`@JsonSubTypes`**，否则 harness 会反向依赖可选模块并产生 Maven 环；可选后端通过各自的 **`FilesystemSpec`** + **`SandboxClientOptions` 子类** 在应用侧装配即可。

**依赖坐标**：只需依赖 **`agentscope-harness`**（或 **`agentscope`** / BOM 管理的 harness 坐标）；**`agentscope-all`** 已随 harness 携带上述实现类。

### 5.8 工作区 bind mount（`BindMountEntry`）

在 **`WorkspaceSpec#getEntries()`** 中可放入 **`io.agentscope.harness.agent.sandbox.layout.BindMountEntry`**（Jackson 多态名 **`bind_mount`**）：**键（map key）** 表示工作区根下的**相对挂载点**（POSIX 风格，如 `data` → `{root}/data`），**`hostPath`** 为宿主（Docker 机 / K8s 节点）上的绝对路径，**`readOnly`** 控制是否只读。

| 后端 | 行为 |
|------|------|
| **Docker** | `docker run` 增加 **`-v host:container:rw|ro`**；`WorkspaceSpecApplier` 不会在容器内复制该路径的内容。 |
| **Kubernetes** | 为每个顶层 bind 增加 **HostPath `Volume`** 与 **`volumeMount`**（路径必须在**调度到的节点**上存在或可按类型创建；生产上需评估安全风险）。 |
| **Daytona / E2B** | 无法在远端云沙箱挂载你的宿主机目录；启动时若 spec 含 bind mount 会打 **WARN**，条目**不生效**。 |

**快照与 `tar`**：持久化工作区时，框架对 bind 挂载子树追加形如 **`tar --exclude=./`** 加上条目相对路径的参数，避免把挂载点下的外部目录打进归档（与 Python 参考实现对齐思路）。若你希望某路径**不进快照**但仍由 applier 写入初始内容，更适合用 **`ephemeral`** 普通文件/目录条目，而不是 bind mount。

**安全**：`hostPath` 来自配置或上游输入时，应限制在可信目录内；bind mount 等价于让容器内进程直接访问该宿主路径。

## 6. 快照与 4-分支恢复

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

## 7. 工作区投影与 Skills 同步

**工作区投影**（`WorkspaceProjectionEntry`）是 harness 将宿主机工作区里的特定目录/文件在**每次沙箱启动时**同步进沙箱的机制，是 Skills 等能力在沙箱内运行的基础。

### 7.1 投影范围

`SandboxFilesystemSpec` 构建 `SandboxContext` 时，默认把以下宿主路径打包进投影：

```
AGENTS.md       ← agent 身份与指令
skills/         ← SkillBox 里所有 Skill 的目录（含 SKILL.md 和脚本文件）
subagents/      ← 子 agent 规格文件
knowledge/      ← 领域知识文件
```

可通过 `SandboxFilesystemSpec#workspaceProjectionRoots(List<String>)` 自定义要投影的根路径，或通过 `workspaceProjectionEnabled(false)` 完全关闭。

### 7.2 投影如何工作

`WorkspaceProjectionApplier` 在 `Sandbox.start()` 末尾执行：

1. 遍历所有 `WorkspaceProjectionEntry`，收集宿主侧的文件集合，按路径排序后计算 **SHA-256 内容哈希**。
2. 将这批文件打包成 tar，通过 `Sandbox.hydrateWorkspace(archive)` 解压到沙箱工作区内对应路径。
3. 把本次哈希存入 `SandboxState.workspaceProjectionHash`；下次启动时若哈希不变，**跳过投影**（避免重复传输）。

这意味着：宿主机上 `skills/` 的内容更新后，下次沙箱 start 时哈希变化，新版文件自动同步进去；沙箱内对 skill 文件的修改不会反向同步回宿主机。

### 7.3 Skills 在沙箱内怎么执行

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

### 7.4 Shell 命令与脚本的状态持久化

`ShellExecuteTool` 调用 `AbstractSandboxFilesystem.execute(cmd, timeout)` → `Sandbox.exec(cmd, timeout)`，在沙箱内执行命令。命令对文件系统的所有更改（新建文件、安装包、写日志等）都保留在沙箱的 overlay/容器内。`stop()` 时这些状态随 tar 快照持久化，下次 `start()` 恢复。

因此，跨 `call` 的**状态是完整保留的**：

```
call 1: shell_execute("pip install pandas")   → pandas 装进沙箱
call 2: shell_execute("python analyze.py")    → 直接可用，无需重装
call 3: shell_execute("cat results.csv")      → 读 call 2 产生的文件
```

## 8. 状态：`SandboxStateStore` 与 `Session`

- **`SandboxStateStore`**：抽象「与某次隔离键绑定的沙箱元数据（sessionId + 快照引用）」的持久化。便于替换为自定义实现；在 **`SandboxFilesystemSpec#sandboxStateStore`** 上配置（未设置则走默认）。
- **默认 `SessionSandboxStateStore`**：依赖构建时选定的 `Session`（与 `SessionPersistenceHook` 等共用的**会话抽象**；若你使用 Redis 等分布式 `Session`，沙箱元数据可随之跨进程可见）。
- **`WorkspaceSession`** 仍负责**工作区布局下的 per-session 配置**；**不要**将 `WorkspaceSession` 的 JSON 与「沙箱 state JSON」混为同一套职责——沙箱的 resume 数据以 **`SandboxStateStore`** 为准。

## 9. 并发控制：`SandboxExecutionGuard`

### 9.1 并发安全边界

不同 `IsolationScope` 对并发的保证程度不同：

| 范围 | 并发安全性 | 说明 |
|------|-----------|------|
| `SESSION` | ✅ 天然隔离 | 每个 session 独占自己的 state slot，不同 session 的请求互不干扰 |
| `USER` | ✅ 默认安全 | 同一用户的多个 session 顺序复用（`checkRunning=true` 保证单实例不并发；多实例下最后写入覆盖，但沙箱本身不会被并发污染） |
| `AGENT` | ⚠️ 需要额外保证 | 所有用户/会话共享同一 state slot；多副本下并发写可能导致快照和 state 互相覆盖 |
| `GLOBAL` | ⚠️ 需要额外保证 | 与 `AGENT` 同理，范围更大 |

`SESSION` 和 `USER` 范围下，单实例的 `checkRunning=true`（默认）已足够；**`AGENT` 和 `GLOBAL` 在多副本部署时需要显式配置 `SandboxExecutionGuard`** 来串行化对共享 slot 的访问。

### 9.2 `SandboxExecutionGuard` 接口

```java
@FunctionalInterface
public interface SandboxExecutionGuard {

    // 阻塞直到获得 key 对应 slot 的执行权，返回持有句柄
    SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException;

    // 默认：无锁，行为与未配置时完全一致
    static SandboxExecutionGuard noop() { ... }
}
```

**生命周期**：guard 在 `acquire` 之前介入，`lease.close()` 在 `release()` 完成之后由 harness 自动调用，覆盖整个 call 窗口：

```
tryEnter(key)          ← 可能在此阻塞，直到上一个 call 完成
  └─ acquire / resume sandbox
  └─ sandbox.start()
  └─ [agent call 执行中]
  └─ persistState()
  └─ sandbox.stop() + shutdown()
lease.close()          ← 释放执行权，下一个等待方可进入
```

Priority 1（`externalSandbox`）和 Priority 2（`externalSandboxState`）不经过 guard——用户自管理的 sandbox 由调用方负责并发控制。

### 9.3 内置 Redis 实现

`RedisSandboxExecutionGuard` 使用 Redis `SET NX PX` 租约实现分布式互斥，**与 `RedisSnapshotSpec` 共用同一 `UnifiedJedis` 实例**，不引入额外依赖：

```java
UnifiedJedis jedis = new JedisPooled("redis-host", 6379);

// 与 RedisSnapshotSpec 共用同一 jedis 实例
SandboxExecutionGuard guard = RedisSandboxExecutionGuard.builder(jedis)
    .leaseTtl(Duration.ofMinutes(30))      // 须大于最坏情况 call 耗时
    .retryInterval(Duration.ofMillis(500)) // 轮询间隔
    .keyPrefix("myapp:sandbox:lock:")      // 可选，多环境隔离时使用
    .build();

HarnessAgent.builder()
    .name("shared-agent")
    .model(model)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.AGENT)
        .snapshotSpec(new RedisSnapshotSpec(jedis, null, null))
        .executionGuard(guard))            // ← 串行化 AGENT 维度的并发访问
    .sandboxDistributed(SandboxDistributedOptions.builder()
        .session(redisSession)
        .requireDistributed(true)
        .build())
    .build();
```

Redis key 格式为 `<keyPrefix><scope_lower>:<value>`，例如：

- `myapp:sandbox:lock:agent:shared-agent`
- `myapp:sandbox:lock:global:__global__`

**TTL 说明**：TTL 是安全阀而非正确性保证。若某次 call 超过 TTL，Redis 自动释放锁，下一个等待方可进入——这防止了进程崩溃导致的永久死锁，但不能保证超时 call 本身的状态安全。请将 `leaseTtl` 设置为实际 call 时长（含 LLM 延迟、重试）的合理上界。

### 9.4 自定义实现参考

`SandboxExecutionGuard` 是 `@FunctionalInterface`，任何锁后端都可以接入：

```java
// 示例：基于 JVM 内存的 Semaphore（单进程多线程场景）
Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

SandboxExecutionGuard jvmGuard = key -> {
    Semaphore sem = semaphores.computeIfAbsent(key.toString(), k -> new Semaphore(1));
    sem.acquire();           // 阻塞直到可用，响应 InterruptedException
    return sem::release;     // SandboxLease：释放信号量
};
```

实现约定：

1. `tryEnter` 阻塞直到获得执行权或线程被中断（抛 `InterruptedException`）
2. `lease.close()` 必须幂等，不得抛出异常（失败只能内部 log）
3. 实现必须线程安全

## 10. 分布式与 `sandboxDistributed`

当多副本或无状态 worker 要共享**同一条逻辑会话**的沙箱恢复能力时，需要：

- **分布式 `Session`**（如 `RedisSession`），而不仅是默认的 `WorkspaceSession` 文件后端；以及
- 非 no-op 的 **`SandboxSnapshotSpec`**（将工作区打成可再拉取的归档），在「必须分布式」的校验下才会通过。

`HarnessAgent.Builder#sandboxDistributed(SandboxDistributedOptions)` 可统一下发：

- 覆盖 **`snapshotSpec`**（若提供）；**`IsolationScope` 只在 `SandboxFilesystemSpec` 上配置**，不在此重复；
- 在选项中**显式指定**用于沙箱的 `Session`（若与主 `session` 不同）；
- 使用 `SandboxDistributedOptions#oss` / `#redis` 等辅助构造常见组合（见类 JavaDoc）。

若 `requireDistributed` 为 true 而当前 `effectiveSession` 仍是 `WorkspaceSession` 或快照为 no-op，构建会 **fail-fast**。

## 11. 与三种 Filesystem 模式怎么选

沙箱是三种**声明式**配置之一。完整对比见 [Filesystem](./filesystem.md#三种声明式模式)；此处只给决策要点：

| 你更需要 | 推荐模式 |
|----------|----------|
| 多实例共享 `MEMORY.md`、会话日志等到 KV，**不要**在宿主跑 shell | `RemoteFilesystemSpec`（见 [Filesystem — 模式一](./filesystem.md)） |
| 单进程/本机、信任 shell、**不要**另起沙箱 | `LocalFilesystemSpec` 或默认本机 + shell（见 [Filesystem — 模式三](./filesystem.md)） |
| **隔离执行**、命令与文件落沙箱、**长会话恢复**、可选**快照 + 集群** | **`SandboxFilesystemSpec`（本文）+ 可选 `sandboxDistributed`** |

## 12. 子 Agent

已启用 `SubagentsHook` 时，若主 agent 在沙箱模式下构建，**子 agent 的 filesystem 会复用**同一 `SandboxBackedFilesystem` 的会话绑定策略（以当前实现为准，便于在同一次编排树内共享环境）。子 agent 本身仍是独立 `ReActAgent`；隔离边界与主 agent 的沙箱 spec 一致。

## 13. 延伸阅读

- [Filesystem](./filesystem.md) — 类层次、三种模式、`abstractFilesystem` 逃生口
- [工具](./tool.md) — `FilesystemTool`、`ShellExecuteTool` 入参
- [会话](./session.md) — `Session` 与 `WorkspaceSession`
- [架构](./architecture.md) — Hook 协作与时序
