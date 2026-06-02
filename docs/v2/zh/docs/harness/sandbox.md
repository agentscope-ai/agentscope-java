---
title: "沙箱（Sandbox）"
description: "隔离执行 + 跨调用恢复 + 多副本部署"
---

> 三种文件系统模式的对比见 [文件系统](./filesystem)。本文专门讲沙箱模式怎么用。

## 沙箱解决什么

把 agent 的**文件操作和命令执行**收到一个隔离环境里，宿主完全不参与。同时给你三个额外好处：

1. **执行边界** —— 不可信用户输入、奇怪的脚本、可能 `rm -rf` 的命令都关进沙箱，宿主无感。
2. **跨调用恢复** —— 不止恢复对话状态：连同 `pip install`、`npm install`、生成的临时文件这些可执行环境也会被快照保存，下次 `call()` 在同一沙箱里继续，不需要重装。
3. **多副本可用** —— 跨副本/跨进程对同一逻辑用户提供服务时，可以让沙箱状态共享同一个 slot，任意节点都能 resume 出同一份工作区。

## 一个最小例子

最简：本地 Docker，按对话隔离。

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("code-agent")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04"))
    .build();

agent.call(msg, RuntimeContext.builder()
    .sessionId("user-1-conv-1")
    .build()).block();
```

`sessionId` 不同 → 沙箱不同；`call()` 之间相同 sessionId → 自动复用同一沙箱（或从快照恢复）。

## IsolationScope —— 谁和谁共享同一沙箱

最常调的参数是 `IsolationScope`：

| Scope | 谁共享 | 典型场景 |
|-------|--------|---------|
| `SESSION`（默认） | 每个 sessionId 独立 | 多用户 SaaS，每段对话各跑各的 |
| `USER` | 同 `userId` 的多个 session 共享 | 同一用户跨会话共享工作区（含分布式部署） |
| `AGENT` | 这个 agent 的所有用户 / 会话共享 | 公共工具型 agent |
| `GLOBAL` | 一个 store 内全局共享 | 谨慎使用 |

```java
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.USER))
```

`SESSION` 是天然并发安全（每个 session 自己一份）；`USER` / `AGENT` / `GLOBAL` 多副本部署时建议配并发互斥（见下面的"并发控制"）。

## 跨调用恢复 = 快照

沙箱在每次 `call()` 结束时把工作区状态打包成快照存起来；下次 `call()` 开始时按情况恢复：

- 容器还在 + 工作区还在 → 直接接着用（最快）
- 容器没了 → 拿快照重新起一个，恢复工作区
- 没快照 → 按 `WorkspaceSpec` 全量初始化（冷启动）

快照存到哪里取决于你配的 `snapshotSpec`：

| 选项 | 适合 |
|------|------|
| `NoopSnapshotSpec`（默认） | 不持久化；容器没了就走冷启动 |
| `LocalSnapshotSpec` | 宿主本地文件（单机长期运行） |
| `OssSnapshotSpec` | OSS / S3 兼容存储（多副本） |
| `RedisSnapshotSpec` | Redis（低延迟、小工作区） |

```java
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .snapshotSpec(new OssSnapshotSpec(ossClient, "my-bucket", "agentscope/")))
```

`AGENTS.md` / `skills/` / `subagents/` / `knowledge/` 等宿主侧的工作区文件会在每次沙箱启动时同步进沙箱（按内容哈希增量）。你改了 `skills/` 里的脚本，下次 `call()` 沙箱里就是新版。

## 分布式部署

多副本部署同一个 agent，要让任意副本都能接住同一用户的对话，需要：

1. 一个分布式 `Session`（例如基于 Redis 的实现）
2. 一个非 `NoopSnapshotSpec` 的快照（OSS / Redis 等远端存储）
3. `IsolationScope` 选 `USER` / `AGENT` / `GLOBAL` 中合适的

为了把这些一起声明，用 `sandboxDistributed(...)`：

```java
HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .filesystem(new DockerFilesystemSpec()
        .image("ubuntu:24.04")
        .isolationScope(IsolationScope.USER))
    .sandboxDistributed(SandboxDistributedOptions.oss(redisSession, ossSnapshotSpec))
    .build();
```

`requireDistributed=true` 时如果实际 Session / snapshot 不满足条件，构建期会直接报错，避免上线后才发现。

## 并发控制（多副本场景）

`USER` / `AGENT` / `GLOBAL` 模式在多副本下，两个副本同时处理同一个用户的请求会都把状态写到同一个 slot，最后写入的为准。如果你不想这样，加一把分布式锁——内置一个基于 Redis 的实现：

```java
SandboxExecutionGuard guard = RedisSandboxExecutionGuard.builder(jedis)
    .leaseTtl(Duration.ofMinutes(30))        // 比最坏情况 call 时长稍长
    .retryInterval(Duration.ofMillis(500))
    .build();

.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.USER)
    .snapshotSpec(redisSnapshotSpec)
    .executionGuard(guard))
```

锁的 key 会按 scope 自动分桶（`USER` → 按 userId，`AGENT` → 按 agent 名）。也可以实现 `SandboxExecutionGuard` 接口接其他锁后端（DB / Zookeeper / etcd 等）。

## 自管沙箱实例（高级）

默认沙箱的整个生命周期由框架托管。三种"我自己管"的场景：

**1. 我已经启动好一个容器，想让 agent 用它**

```java
Sandbox mySandbox = dockerClient.create(workspaceSpec, snapshotSpec, options);
mySandbox.start();

SandboxContext callCtx = SandboxContext.builder()
    .client(dockerClient)
    .externalSandbox(mySandbox)       // 框架在 call 结束时只 stop()，不 shutdown()
    .build();

agent.call(msgs, RuntimeContext.builder()
    .sessionId("my-session")
    .sandboxContext(callCtx)
    .build()).block();

mySandbox.shutdown();
```

**2. 我有一个具体的快照串，想恢复到那个时刻**

```java
SandboxState savedState = dockerClient.deserializeState(savedStateJson);
SandboxContext callCtx = SandboxContext.builder()
    .client(dockerClient)
    .externalSandboxState(savedState)  // 框架按这个 state 恢复，但生命周期仍由框架管
    .build();
```

**3. 多个 agent 共享同一个沙箱**

把同一个 `externalSandbox` 透传给多个 agent 的 `call()`，最后由你自己 `shutdown()`。

## 沙箱后端怎么选

| 后端 | 适合 |
|------|------|
| **Docker** | 本地开发 / 单机 / 信任 shell |
| **Kubernetes** | 自建 K8s 集群、节点级 bind mount |
| **Daytona** | 通用托管沙箱 HTTP API |
| **E2B** | 通用托管沙箱 + 平台原生快照 |
| **AgentRun** | 阿里云托管沙箱（函数计算 FC 3.0），实例级 NAS / OSS 动态挂载，中国大陆区域低延迟。在 Harness 里和 Docker / K8s / Daytona / E2B 等后端等价对待，模板配置 / RAM 权限 / NAS-first 等接入细节归在 integration 文档下 |

所有后端实现同一组接口，agent 代码、工具集、`AGENTS.md` 都不用变。

## 工作区怎么映射进沙箱

宿主侧 `workspace/` 下的关键文件（`AGENTS.md`、`skills/`、`subagents/`、`knowledge/`）在每次沙箱启动时同步进去；按内容哈希增量，不变就跳过传输。

需要把宿主的某个目录 bind 进沙箱（例如代码仓库），用 `BindMountEntry`（仅 Docker / K8s 支持；Daytona / E2B 等托管沙箱在云上跑，自然不能挂宿主目录）。

Sandbox 内对文件的修改不会反向同步回宿主——你想取沙箱里的产物，让 agent 自己 `read_file`。

## 实现自己的沙箱后端

需要接入 Docker 以外的隔离环境（自建远端执行器、商用沙箱 API、本地 mock 等），不需要改 Harness 源码——实现几个契约接口然后传给 `filesystem(...)` 就行。参考 `agentscope-harness` 测试里的 `InMemorySandbox` 系列，是最小可改造骨架。

## 相关文档

- [文件系统](./filesystem) — 三种声明式模式对比
- [工作区](./workspace) — `workspace/` 下哪些文件会同步进沙箱
- [架构](./architecture) — 沙箱 acquire / release 在 call() 时序中的位置
