# harness-example-remote — Filesystem **模式一（复合 + 共享存储 / Remote）**

本模块用最小的 **Chinook Text-to-SQL Data Agent** 业务，演示在 `HarnessAgent` 中如何启用 **`RemoteFilesystemSpec`**（与 [`docs/zh/harness/filesystem.md`](../../docs/zh/harness/filesystem.md) 中的 **模式一**、以及 `HarnessAgent.Builder#filesystem(RemoteFilesystemSpec)` 注释中的 **Mode 1** 一致）。

> 命名里的 **remote** 指「长期记忆、会话落盘、knowledge 等走 **BaseStore（远程 KV）**」，不是 RPC 远程桌面。生产上通常用 **Redis** 等实现 `BaseStore`；本示例用 **`InMemoryStore`** 单机模拟。

---

## 模式一在 Harness 里是什么

`RemoteFilesystemSpec` 会组合出 **`CompositeFilesystem`**：

- **默认前缀**：纯 **`LocalFilesystem`（无 shell）** —— 放 `skills/`、`AGENTS.md` 等「每副本本地即可」或从镜像带的静态文件。
- **路由到 Store 的前缀**（默认含 `MEMORY.md`、`memory/`、`agents/<agentId>/sessions/`）：**`RemoteFilesystem`**，数据落在 **`BaseStore`**，由 **`IsolationScope`** 决定命名空间（SESSION / USER / AGENT / GLOBAL）。

因此：**模式一的设计目标是多副本共享记忆与日志，且刻意不在宿主上开放 shell**（与模式三不同，与模式二「shell 在沙箱里」也不同）。

---

## 本示例如何实现「Remote 模式」

### 1. 显式 `.filesystem(RemoteFilesystemSpec)`

`DataAgentService` 中：

```java
InMemoryStore store = new InMemoryStore();
remoteSpec =
    new RemoteFilesystemSpec(store)
        .isolationScope(IsolationScope.USER)
        .addSharedPrefix("knowledge/");
// ...
HarnessAgent.builder()
    .workspace(hostWorkspace)
    .filesystem(remoteSpec)
    .session(appSession)
    ...
```

- **`InMemoryStore`**：实现 `BaseStore`，单机模拟 **Redis**。
- **`isolationScope(USER)`**：与沙箱示例类似，**同一 `userId`** 共享 store 命名空间下的 `MEMORY.md` / `memory/` / `knowledge/`（本示例额外把 `knowledge/` 加进共享前缀，便于多副本读到同一份领域知识）。
- **没有 `ShellExecuteTool`**：Agent 若需改文件，应使用 **`read_file` / `write_file` / `grep_files`**（见本模块 `AGENTS.md` 说明）。

### 2. 必须提供「非 WorkspaceSession」的 `Session`

`HarnessAgent` 在检测到 `RemoteFilesystemSpec` 时，会要求 **有效 Session 不能仍是纯本地的 `WorkspaceSession`**（否则多副本无法共享会话状态）。因此本示例使用：

```java
.session(new InMemorySession())
```

生产环境请换成 **`RedisSession`** 等分布式实现（见 `agentscope-extensions-session-redis` 等模块）。

### 3. 与模式二、三的对照

| 模式 | 本仓库示例 | Shell |
|------|------------|-------|
| 模式一 | **本模块** | **无**（宿主无 `execute` 工具） |
| 模式二 | [`harness-example-sandbox`](../harness-example-sandbox/README.md) | 有（沙箱内） |
| 模式三 | [`harness-example-local`](../harness-example-local/README.md) | 有（宿主） |

---

## 运行

```bash
export DASHSCOPE_API_KEY=your_key
# 可选：AGENTSCOPE_MODEL

cd agentscope-java
mvn -pl agentscope-examples/harness-example-remote -am package -DskipTests
java -jar agentscope-examples/harness-example-remote/target/harness-example-remote-*.jar
```

默认端口 **`8788`**（与 sandbox 的 `8787` 错开）。

```bash
curl -s -X POST http://localhost:8788/query \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s1","userId":"alice","question":"How many artists?"}'
```

---

## 相关源码入口

| 文件 | 作用 |
|------|------|
| `DataAgentService.java` | `RemoteFilesystemSpec` + `InMemorySession` + `HarnessAgent` 装配 |
| `WorkspaceClasspathMaterializer.java` | 把 classpath `workspace/` 落到本地临时目录（Composite 的「本地侧」根） |

更多理论见 [`docs/zh/harness/filesystem.md`](../../docs/zh/harness/filesystem.md)。
