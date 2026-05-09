# harness-example-sandbox — Filesystem **模式二（沙箱）**

本模块用最小的 **Chinook Text-to-SQL Data Agent** 业务，演示在 `HarnessAgent` 中如何启用 **`SandboxFilesystemSpec`**（与 [`docs/zh/harness/filesystem.md`](../../docs/zh/harness/filesystem.md) 中的 **模式二**、以及 `HarnessAgent.Builder#filesystem(SandboxFilesystemSpec)` 注释中的 **Mode 2** 一致）。

---

## 模式二在 Harness 里是什么

- **对外**：仍是 `AbstractFilesystem` + `FilesystemTool`；若后端实现 `AbstractSandboxFilesystem`，Harness 会注册 **`ShellExecuteTool`**，命令在**沙箱进程/容器**里执行，而不是宿主 `sh -c`。
- **生命周期**：`SandboxLifecycleHook` 在每次 `call` 前后 **acquire / persist / release** 沙箱，工作区投影、状态落盘由 `SandboxStateStore` + 隔离键描述。
- **与模式一、三的区别**（简表）：

| 模式 | 典型配置 | Shell | 本仓库示例 |
|------|----------|-------|------------|
| 模式一 | `RemoteFilesystemSpec` | 无（宿主侧） | [`harness-example-remote`](../harness-example-remote/README.md) |
| **模式二** | **`SandboxFilesystemSpec`** | **有（在沙箱内）** | **本模块** |
| 模式三 | 不写 `filesystem(...)` 或 `LocalFilesystemSpec` | 有（宿主） | [`harness-example-local`](../harness-example-local/README.md) |

---

## 本示例如何实现「沙箱模式」

### 1. 显式使用 `SandboxFilesystemSpec` 子类

`DataAgentService` 中构建 `HarnessAgent` 时使用：

```java
.filesystem(fsSpec)   // fsSpec 为 InMemorySandboxFilesystemSpec，extends SandboxFilesystemSpec
```

`InMemorySandboxFilesystemSpec` 在本模块的 `support/` 包内：用 **`InMemorySandboxClient`** 在本地 JVM 里分配临时目录，**代替**生产里的 Docker `SandboxClient`；行为上仍是「通过 Sandbox 抽象执行 shell / 投影工作区」。

### 2. 共享 `SandboxStateStore` + `IsolationScope.USER`

- **`SharedInMemorySandboxStateStore`**：内存版 `SandboxStateStore`，模拟多副本共用的 **Redis / 元数据存储**（保存沙箱句柄、工作区根路径等，便于 **resume**）。
- **`fsSpec.isolationScope(IsolationScope.USER).sandboxStateStore(stateStore)`**：同一 `userId` 多次请求（可不同 `sessionId`）**复用同一沙箱工作区**；不同 `userId` 隔离。
- 生产部署时：各实例配置**相同的** `SandboxStateStore` 与 **同一套** `SandboxClient`/镜像策略即可；本示例把复杂度收进内存，便于单机跑通。

### 3. 业务层刻意保持简单

- **Spring Boot**：`POST /query`，body 为 `sessionId`、`userId`、`question`。
- **宿主工作区**：`WorkspaceClasspathMaterializer` 把 classpath 里的 `AGENTS.md`、`skills/`、`knowledge/` 落到临时目录，再经沙箱 **workspace projection** 进入会话工作区（与生产「仓库里带 skills」一致）。
- **Chinook**：`SqliteTool` 直连宿主 materialize 出的 `chinook.db`（JDBC 在 JVM 内执行，与「是否在沙箱里跑 SQL」正交；重点是 **文件与 shell 走沙箱路径**）。

---

## 运行

```bash
export DASHSCOPE_API_KEY=your_key
# 可选：AGENTSCOPE_MODEL（默认 qwen-max）

cd agentscope-java
mvn -pl agentscope-examples/harness-example-sandbox -am package -DskipTests
java -jar agentscope-examples/harness-example-sandbox/target/harness-example-sandbox-*.jar
```

默认端口 **`8787`**（见 `application.properties`）。

```bash
curl -s -X POST http://localhost:8787/query \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s1","userId":"alice","question":"How many artists are in Chinook?"}'
```

---

## 相关源码入口

| 文件 | 作用 |
|------|------|
| `DataAgentService.java` | `HarnessAgent.builder().filesystem(fsSpec)...` |
| `support/InMemorySandboxFilesystemSpec.java` | `SandboxFilesystemSpec` + `SandboxClient` |
| `support/SharedInMemorySandboxStateStore.java` | 分布式沙箱元数据的单机替身 |

更完整的沙箱概念见 [`docs/zh/harness/sandbox.md`](../../docs/zh/harness/sandbox.md)。
