# Sandbox 子系统实现情况总结

## 目标

为 `HarnessAgent` 构建一套完整的 Sandbox（沙箱）子系统，使 Agent 的每次调用都可以在隔离的工作区环境中执行命令和文件操作，同时支持工作区快照（Snapshot）持久化与跨调用恢复。

核心设计原则：

- 通过 **Hook 管道**（`SandboxLifecycleHook`）无侵入地集成到 `HarnessAgent` 的现有流程
- 以 **`SandboxManager`** 为核心调度器，统一管理 Session 的获取与生命周期
- `SandboxSession` 实现类同时扮演 **文件系统后端**（通过 `SandboxBackedFilesystem`），使现有工具（`FilesystemTool`、`ShellExecuteTool`）无需修改
- `HarnessAgent.Builder` 提供简洁的用户 API，支持三种使用模式：SDK 托管、开发者托管、Resume 恢复

---

## 实现计划（阶段划分）

| 阶段 | 内容 | 优先级 |
|------|------|--------|
| P0 | 核心抽象层（接口、异常、状态、Manifest） | 必须 |
| P0 | 快照抽象层（NoopSnapshot、LocalSnapshot） | 必须 |
| P0 | UnixLocal 实现（本地进程沙箱） | 必须 |
| P0 | SandboxLifecycleHook + SandboxSessionAware | 必须 |
| P0 | RuntimeContext 扩展 + HarnessAgent Builder API | 必须 |
| P1 | WorkspaceSession `_sandbox` 状态持久化 + Resume 流程 | 重要 |
| P1 | SandboxManifest 物化（LocalDir / LocalFile / File / Dir） | 重要 |
| P1 | DockerSandboxSession + DockerSandboxClient | 重要 |
| P2 | RemoteSandboxSnapshot（S3 等） | 扩展 |
| P2 | GitRepoEntry Manifest 条目 | 扩展 |
| P2 | 工作区指纹缓存（避免冗余快照恢复） | 优化 |

---

## 实现状态

### ✅ 已完成

#### 核心抽象层（`sandbox/` 包）

| 文件 | 说明 |
|------|------|
| `SandboxErrorCode.java` | 11 个错误码枚举（`EXEC_NONZERO`、`EXEC_TIMEOUT`、`WORKSPACE_START_ERROR` 等） |
| `SandboxException.java` | 基类 + 7 个内部子类（`ExecException`、`ExecTimeoutException`、`SnapshotException` 等） |
| `ExecResult.java` | `record(int exitCode, String stdout, String stderr, boolean truncated)`，带 `ok()`、`combinedOutput()` |
| `SandboxSessionAware.java` | 注入接口：`setSandboxSession(SandboxSession)`、`getSandboxSession()` |
| `SandboxSession.java` | 核心接口：完整生命周期（`start/stop/shutdown/close`）+ exec + 工作区序列化 |
| `SandboxClient.java` | 泛型接口 `<O extends SandboxClientOptions>`：create / resume / delete / 状态序列化 |
| `SandboxClientOptions.java` | 多态基类，`@JsonTypeInfo(NAME)`，已注册 `unix_local`、`docker` |
| `SandboxSessionState.java` | 多态基类，`@JsonTypeInfo(NAME)`，已注册 `unix_local`、`docker` |
| `SandboxManifest.java` | 工作区描述符：`root`、`entries`（LinkedHashMap）、`environment` |
| `SandboxContext.java` | 不可变 Builder，聚合每次调用的沙箱配置（client、options、manifest、snapshotSpec、externalSession） |
| `SandboxAcquireResult.java` | `session + sdkOwned:boolean`，`sdkOwned()` / `developerOwned()` 工厂方法 |
| `SandboxManager.java` | 4 优先级 acquire 逻辑（developer-owned → resume-from-state → resume-from-file → create-new）、release、persistState、clearState |
| `AbstractBaseSandboxSession.java` | 4 分支 start 逻辑（Branch A/B/C/D）、stop/close/exec/persistWorkspace/hydrateWorkspace |
| `ManifestApplier.java` | 递归物化 Manifest：FileEntry / DirEntry / LocalFileEntry / LocalDirEntry / GitRepoEntry（P2 占位 warn） |
| `WorkspaceArchiveExtractor.java` | 安全 tar 解压（使用 commons-compress），路径遍历防护（`..`、绝对路径、null 字节） |
| `SandboxBackedFilesystem.java` | 扩展 `BaseSandboxFilesystem`，实现 `SandboxSessionAware`；exec 代理到 `session.exec()`；upload/download 通过 Base64 编码命令实现 |

#### Manifest 子类型（`sandbox/manifest/` 包）

| 文件 | 说明 |
|------|------|
| `ManifestEntry.java` | 抽象基类，`@JsonTypeInfo`，5 个子类型注册 |
| `FileEntry.java` | 内联文件内容，`content + encoding` |
| `DirEntry.java` | 创建目录，`children: LinkedHashMap`，支持 `.child()` 链式构建 |
| `LocalFileEntry.java` | 从宿主机复制文件，`sourcePath` |
| `LocalDirEntry.java` | 从宿主机复制目录，`sourcePath` |
| `GitRepoEntry.java` | 克隆 Git 仓库（P2 占位），`url + ref` |

#### 快照层（`sandbox/snapshot/` 包）

| 文件 | 说明 |
|------|------|
| `SandboxSnapshot.java` | 接口：`persist / restore / isRestorable / getId / getType`，`@JsonTypeInfo` |
| `SandboxSnapshotSpec.java` | 工厂接口：`build(snapshotId) → SandboxSnapshot` |
| `NoopSandboxSnapshot.java` | 丢弃归档流，`isRestorable()=false` |
| `NoopSnapshotSpec.java` | 始终返回新 `NoopSandboxSnapshot` |
| `LocalSandboxSnapshot.java` | 持久化到 `{basePath}/{id}.tar`，原子写入（tmp + `ATOMIC_MOVE`），路径安全校验 |
| `LocalSnapshotSpec.java` | 工厂，创建 `LocalSandboxSnapshot` |
| `RemoteSandboxSnapshot.java` | 委托到 `RemoteSnapshotClient`（上传/下载/exists） |
| `RemoteSnapshotSpec.java` | 工厂，创建 `RemoteSandboxSnapshot` |
| `RemoteSnapshotClient.java` | 用户扩展接口（S3 等）：`upload / download / exists` |

#### Unix Local 实现（`sandbox/impl/local/` 包）

| 文件 | 说明 |
|------|------|
| `UnixLocalSandboxClientOptions.java` | `workspaceBasePath: String`、`exposedPorts: int[]`，`getType()="unix_local"` |
| `UnixLocalSandboxSessionState.java` | 扩展 `SandboxSessionState`：`workspaceRoot`、`workspaceRootOwned` |
| `UnixLocalSandboxSession.java` | `doExec()` 通过 `ProcessBuilder("sh","-c",cmd)` + 2 线程 stdout/stderr 排空；`doPersistWorkspace()` 通过 `tar -cf - -C <root> .`；`doHydrateWorkspace()` 通过 `WorkspaceArchiveExtractor`；`shutdown()` 删除自有临时目录 |
| `UnixLocalSandboxClient.java` | UUID sessionId，`resolveWorkspaceRoot()`（basePath → `<base>/<sessionId>` 或系统 tmpdir → `<tmp>/<workspaceRootName>`）；Jackson 多态序列化/反序列化 |

#### Docker 实现（`sandbox/impl/docker/` 包）

| 文件 | 说明 |
|------|------|
| `DockerSandboxClientOptions.java` | `image`（默认 `ubuntu:22.04`）、`workspaceRoot`、`environment`、`memorySizeBytes`、`cpuCount`、`exposedPorts` |
| `DockerSandboxSessionState.java` | 扩展 `SandboxSessionState`：`containerId`、`containerName`、`image`、`workspaceRoot`、`containerOwned`，及用于容器重建的资源字段 |
| `DockerSandboxSession.java` | 通过 Docker CLI（`ProcessBuilder`，无 docker-java 依赖）；`start()` 处理 running/stopped/missing 三种容器状态；exec 通过 `docker exec -w <root>`；工作区归档通过 `docker exec tar`；`shutdown()` 执行 `docker stop` + `docker rm` |
| `DockerSandboxClient.java` | 创建/恢复 Docker 沙箱 Session；Jackson 多态序列化/反序列化 |

#### Hook 集成（`hook/` 包）

| 文件 | 说明 |
|------|------|
| `SandboxLifecycleHook.java` | `priority=50`（优先于所有现有 Hook 运行）；`PreCallEvent`：acquire → start → inject session → set ThreadLocal；`PostCallEvent/ErrorEvent`：persistState → release → clearSession（best-effort） |

#### 现有文件修改

| 文件 | 修改内容 |
|------|---------|
| `pom.xml` | 添加 `commons-compress 1.27.1` 依赖 |
| `RuntimeContext.java` | 添加 `sandboxContext: SandboxContext` 字段 + Builder `sandboxContext()` 方法 |
| `WorkspaceSession.java` | 添加 `saveSandboxState(SessionKey, String)`、`loadSandboxStateJson(SessionKey)`、`deleteSandboxState(SessionKey)`、`resolveSessionDir(SessionKey)` |
| `SandboxBackedFilesystem.java` | 添加 `configureNamespace(NamespaceFactory)` 公开方法 |
| `HarnessAgent.java` | Builder 添加 `sandboxClient/Options/SnapshotSpec/defaultManifest` 字段与方法；`build()` 中创建 `SandboxBackedFilesystem`、`SandboxManager`、`SandboxLifecycleHook`；`ensureSessionDefaults()` 保留/注入 `sandboxContext`；`bindRuntimeContext()` 传播上下文到 `sandboxLifecycleHook`；subagent factory 传播 `sandboxFs` |

---

## 关键设计决策

### 1. Session 与 Filesystem 融合策略

采用 **直接继承** `BaseSandboxFilesystem` 方案（短期方案 A）：

- `UnixLocalSandboxSession` 和 `DockerSandboxSession` 均扩展 `AbstractBaseSandboxSession`
- `SandboxBackedFilesystem` 作为代理层，通过 `SandboxSessionAware` 接口接收注入的 Session
- 现有工具（`FilesystemTool`、`ShellExecuteTool`）无需任何修改

### 2. Session 获取优先级（4-Branch Acquire）

```
1. SandboxContext.externalSession != null   → developer-owned（不调用 start()）
2. SandboxContext.externalSessionState != null → SDK-owned resume
3. WorkspaceSession 中存在 _sandbox.json    → SDK-owned resume（跨调用自动恢复）
4. 以上均不满足                              → SDK-owned create（全新初始化）
```

### 3. 工作区初始化（4-Branch Start）

```
Branch A: workspaceRootReady=true  & 目录仍存在  → 仅应用 ephemeral 条目
Branch B: workspaceRootReady=true  & 目录已丢失  → 从快照恢复 + ephemeral 条目
Branch C: workspaceRootReady=false & 快照可恢复  → 从快照 hydrate + 全量 Manifest
Branch D: workspaceRootReady=false & 无可用快照  → 全量 Manifest 初始化
```

### 4. Docker 实现无第三方依赖

Docker 实现通过 Docker CLI（`docker exec`、`docker run` 等）调用，不引入 `docker-java` 库，
保持 `agentscope-harness` 的依赖简洁性。

### 5. 子 Agent 传播策略

默认策略：子 Agent 使用隔离的沙箱（独立 create）。
若 `SandboxContext.externalSession` 被显式传递，则使用共享模式。
通过 `buildGeneralPurposeFactory()` 将 `capturedSandboxFs` 传播给子 Agent，但不传递生命周期管理（`sandboxClient`）。

---

## 模块结构（最终）

```
agentscope-harness/src/main/java/io/agentscope/harness/agent/
├── sandbox/
│   ├── SandboxSession.java
│   ├── SandboxClient.java
│   ├── SandboxClientOptions.java           ← @JsonSubTypes: unix_local, docker
│   ├── SandboxSessionState.java            ← @JsonSubTypes: unix_local, docker
│   ├── SandboxManifest.java
│   ├── SandboxContext.java
│   ├── SandboxAcquireResult.java
│   ├── SandboxManager.java
│   ├── SandboxSessionAware.java
│   ├── SandboxBackedFilesystem.java
│   ├── SandboxException.java
│   ├── SandboxErrorCode.java
│   ├── ExecResult.java
│   ├── AbstractBaseSandboxSession.java
│   ├── ManifestApplier.java
│   ├── WorkspaceArchiveExtractor.java
│   ├── manifest/
│   │   ├── ManifestEntry.java
│   │   ├── FileEntry.java
│   │   ├── DirEntry.java
│   │   ├── LocalFileEntry.java
│   │   ├── LocalDirEntry.java
│   │   └── GitRepoEntry.java
│   ├── snapshot/
│   │   ├── SandboxSnapshot.java
│   │   ├── SandboxSnapshotSpec.java
│   │   ├── NoopSandboxSnapshot.java
│   │   ├── NoopSnapshotSpec.java
│   │   ├── LocalSandboxSnapshot.java
│   │   ├── LocalSnapshotSpec.java
│   │   ├── RemoteSandboxSnapshot.java
│   │   ├── RemoteSnapshotSpec.java
│   │   └── RemoteSnapshotClient.java
│   └── impl/
│       ├── local/
│       │   ├── UnixLocalSandboxClientOptions.java
│       │   ├── UnixLocalSandboxSessionState.java
│       │   ├── UnixLocalSandboxSession.java
│       │   └── UnixLocalSandboxClient.java
│       └── docker/
│           ├── DockerSandboxClientOptions.java
│           ├── DockerSandboxSessionState.java
│           ├── DockerSandboxSession.java
│           └── DockerSandboxClient.java
└── hook/
    └── SandboxLifecycleHook.java
```

---

## 待实现（P2 / 未来方向）

| 项目 | 说明 |
|------|------|
| `GitRepoEntry` 物化 | 当前仅打印 warn，需实现 `git clone` 逻辑 |
| 工作区指纹缓存 | 避免相同工作区内容重复触发快照恢复，Python 参考实现中的 `snapshotFingerprint` 字段 |
| 子 Agent 共享沙箱 API | 提供更显式的 API 让父 Agent 将自己的 Session 传递给子 Agent |
| Docker Windows 支持 | 当前 Docker 实现仅测试过 Unix 环境 |
| `SandboxFilesystemAdapter` | 将 Session 生命周期与 Filesystem 操作彻底解耦（长期重构方向） |

---

## 测试状态

- `agentscope-harness` 模块共 6 个测试，5 个通过
- `HarnessAgentIntegrationExampleTest.example_fullWorkspace_singleTurn_seesSessionSubagentsAndWorkspaceContext` 失败（Line 150，`expected: <true> but was: <false>`）——此为**预存在缺陷**，在本次 Sandbox 实现开始前已存在，与沙箱代码无关
