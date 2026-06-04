---
title: "文件系统（Filesystem）"
description: "三种部署模式：本机 + shell / 共享存储 / 沙箱；多用户隔离"
---

## 作用

`HarnessAgent` 把 agent 对**工作区**的访问从"一定是本机磁盘"抽象成统一接口。所有文件工具（`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files`）和可选的 `execute`（shell）都从这个抽象走。

这样做让你能在三种部署模式之间切换，而**不改 agent 代码**：

- 本机 + shell —— 单进程、本地、信任环境；
- 共享存储 —— 多副本 / 多 pod 共享同一份长期记忆；
- 沙箱 —— 文件与命令都在隔离容器里执行，跨调用恢复同一份工作区。

## 三种声明式模式

在 `HarnessAgent.Builder` 上用 `filesystem(...)` 三选一（不调就是默认模式 3）：

| 模式 | 配置 | 提供 shell？ | 适用场景 |
|------|------|-------------|---------|
| **1 · 共享存储** | `filesystem(new RemoteFilesystemSpec(store))` | ❌ | 多副本要共享 `MEMORY.md` / 会话日志 / 子任务到 KV；**不希望在宿主上跑 shell** |
| **2 · 沙箱** | `filesystem(new DockerFilesystemSpec()...)` 或 K8s / Daytona / E2B / AgentRun | ✅（在沙箱内） | 隔离执行、跨调用恢复同一份工作区、可选快照 + 分布式 |
| **3 · 本机 + shell**（默认） | `filesystem(new LocalFilesystemSpec()...)` 或**不写** | ✅（宿主 `sh -c`） | 单进程 / 本机 / 信任环境 / 简单脚本与测试 |

> `filesystem(...)` 与 `abstractFilesystem(...)` 互斥；后者是给完全自管文件系统的逃生口，正常用法不需要。

### 模式 1：共享存储

适合"多副本，但用户的长期记忆要一致"。把一个 KV 存储（Redis / JDBC / 自定义）传进去，框架自动把 `MEMORY.md`、`memory/`、会话日志、子任务记录等路由到这个存储：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("store")
    .model(model)
    .workspace(workspace)
    .filesystem(new RemoteFilesystemSpec(redisStore)
        .isolationScope(IsolationScope.USER))   // 按 userId 分命名空间
    .build();
```

这种模式**不提供 shell**——故意的：要 shell 请用模式 2（沙箱）或 3（本机）。

### 模式 2：沙箱

适合"代码会执行不可信操作、或要隔离生产环境"。所有文件操作和 shell 命令都发到沙箱里执行，宿主完全不受影响。沙箱可以做快照，下次 `call()` 时连同 `node_modules`、`pip install` 都能恢复回来：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("sandbox")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.SESSION))
    .build();
```

详细见 [沙箱](./sandbox)。可选后端：Docker / Kubernetes / Daytona / E2B / AgentRun（阿里云）。

### 模式 3：本机 + shell（默认）

什么都不写就是这个：工作区落到 `${cwd}/.agentscope/workspace/`，shell 在宿主上跑：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("local")
    .model(model)
    .workspace(workspace)
    // .filesystem(...) 不写 = 本机 + shell
    .build();
```

需要调整超时、环境变量等：

```java
.filesystem(new LocalFilesystemSpec()
    .executeTimeoutSeconds(120)
    .env("MY_VAR", "value")
    .inheritEnv(false))
```

## IsolationScope —— 多用户与多副本怎么分桶

模式 1（共享存储）和模式 2（沙箱）都用同一个 `IsolationScope` 概念，决定**谁和谁共享同一份状态**：

| Scope | 含义 | 典型场景 |
|-------|------|---------|
| `SESSION`（默认） | 每个 sessionId 独立 | 多用户 SaaS，每段对话各跑各的 |
| `USER` | 同一 `userId` 跨 session 共享 | 同一用户的多个会话共享长期记忆（分布式部署） |
| `AGENT` | 这个 agent 的所有用户/会话共享 | 公共知识库型 agent |
| `GLOBAL` | 全局共享一份 | 谨慎使用 |

例子：要让 alice 在不同设备上的多个对话共享同一份长期记忆——选 `USER`，再把 `userId="alice"` 放进 `RuntimeContext`。

## 多用户隔离怎么实现

`RuntimeContext.userId` 是切多用户的钥匙：

- **本机模式**：用户级文件落在 `workspace/<userId>/...`，例如 `workspace/alice/skills/code-reviewer/SKILL.md` 只对 alice 可见；
- **共享存储模式**：作为 KV 命名空间前缀，分布式副本天然共享；
- **沙箱模式**：作为沙箱状态的 slot key（搭配 `IsolationScope.USER`）。

`userId` 不传的情况下走单租户默认，所有人共享一个根。

## 工作区里的两层读取

`AGENTS.md`、`MEMORY.md`、`KNOWLEDGE.md` 等关键文件在读取时有"两层兜底"：先看你配的文件系统后端，没有再退回本地磁盘。这对**模式 1（共享存储）下的"模板文件"** 很有用：第一个副本启动时本地有 `AGENTS.md` 模板，立刻可用；后续副本会从共享存储读出最新版本。

写永远走配置的文件系统后端。

## 完全自管：`abstractFilesystem(...)`

如果三种模式都不合适，可以传一个完全自己实现的文件系统：

```java
HarnessAgent.builder()
    ...
    .abstractFilesystem(myCustomFilesystem)   // 与上面的 filesystem(...) 互斥
    .build();
```

通常不需要——三种模式覆盖了 95% 的场景。

## 相关文档

- [沙箱](./sandbox/index) — 模式 2 的细节
- [工作区](./workspace) — 上面两层读取里"下层"的来源
- [工具](./tool) — `read_file` / `write_file` / `execute` 等参数
- [架构](./architecture) — 文件系统与运行时上下文如何协作
