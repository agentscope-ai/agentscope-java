# 分布式存储（Distributed Store）

AgentScope 将所有需要分布式持久化的组件统一到 `DistributedStore` 接口下。一行配置即可让 Agent 的状态、工作区文件系统、沙箱快照和并发锁全部切到同一个分布式后端。

## 快速上手

```java
// Redis 一键配置
DistributedStore store = RedisDistributedStore.fromJedis(
        new JedisPooled("redis://localhost:6379"));

HarnessAgent agent = HarnessAgent.builder()
    .name("my-agent")
    .model("dashscope:qwen-plus")
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()            // baseStore 自动注入
            .isolationScope(IsolationScope.USER))
    .build();
```

## 能力矩阵

| 功能组件 | 接口 | Redis | OSS | MySQL |
|---------|------|:-----:|:---:|:-----:|
| Agent 状态持久化 | `AgentStateStore` | `RedisAgentStateStore` | `AliyunOssAgentStateStore` / `AwsS3AgentStateStore` / `TencentCosAgentStateStore` | `MysqlAgentStateStore` |
| 工作区文件系统 KV | `BaseStore` | `RedisStore` | `AliyunOssBaseStore` / `AwsS3BaseStore` / `TencentCosBaseStore` | `JdbcStore` |
| 沙箱快照 | `SandboxSnapshotSpec` | `RedisSnapshotSpec` | `AliyunOssSnapshotSpec` / `AwsS3SnapshotSpec` / `TencentCosSnapshotSpec` | `JdbcSnapshotSpec` |
| 沙箱并发锁 | `SandboxExecutionGuard` | `RedisSandboxExecutionGuard` | — | `JdbcSandboxExecutionGuard` |

> OSS 不提供 `SandboxExecutionGuard`——对象存储不适合做分布式锁。需要 sandbox 并发控制的 OSS 用户，用 `DistributedStore.builder()` 混入 Redis 的 guard。

## 混合后端

不同组件可以来自不同的存储后端：

```java
DistributedStore mysql = MysqlDistributedStore.create(dataSource);
DistributedStore redis = RedisDistributedStore.fromJedis(jedis);

// MySQL 管状态和文件，Redis 管沙箱锁和快照
DistributedStore mixed = DistributedStore.builder()
    .agentStateStore(mysql.agentStateStore())
    .baseStore(mysql.baseStore())
    .sandboxSnapshotSpec(redis.sandboxSnapshotSpec())
    .sandboxExecutionGuard(redis.sandboxExecutionGuard())
    .build();

HarnessAgent.builder()
    .distributedStore(mixed)
    .filesystem(new DockerFilesystemSpec()
            .image("ubuntu:24.04"))
    .build();
```

## 各组件说明

### AgentStateStore — Agent 状态持久化

Agent 的对话上下文、压缩摘要、权限规则、Plan Mode 状态等，通过 `(userId, sessionId)` 寻址。`distributedStore` 自动注入，也可通过 `.stateStore(...)` 单独覆盖。

### BaseStore — 工作区文件系统 KV

`RemoteFilesystemSpec` 的存储后端，将 `MEMORY.md`、`memory/`、`skills/`、`sessions/` 等路径路由到共享 KV 存储。`distributedStore` 自动注入到 `RemoteFilesystemSpec`（如果用的是无参构造器）。

### SandboxSnapshotSpec — 沙箱快照

将 Docker/K8s 等沙箱的工作区打成 tar 包持久化，下次 `call()` 自动恢复。`distributedStore` 自动注入到 `SandboxFilesystemSpec`。

### SandboxExecutionGuard — 沙箱并发锁

`AGENT` / `GLOBAL` 隔离范围在多副本下需要分布式锁防止并发冲突。`distributedStore` 自动注入到 `SandboxFilesystemSpec`。

## 优先级

```
显式 builder 方法 (.stateStore(), .filesystem() 上的 .snapshotSpec() 等)
    > distributedStore 自动注入
        > 本地默认 (JsonFileAgentStateStore, NoopSnapshotSpec 等)
```

## 后端详细文档

- [Redis](redis.md) — 最全功能覆盖，多副本生产首选
- [MySQL / JDBC](mysql.md) — 已有关系型数据库的场景
- [阿里云 OSS](oss.md) — 对象存储，大容量快照首选
