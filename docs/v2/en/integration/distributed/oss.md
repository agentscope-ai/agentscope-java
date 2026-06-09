# Alibaba Cloud OSS

`agentscope-extensions-oss` provides distributed storage backed by Alibaba Cloud Object Storage Service (OSS), ideal for large-capacity data and Alibaba Cloud ecosystems.

## Dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-oss</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## One-Line Setup

```java
import io.agentscope.extensions.oss.OssDistributedStore;

OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
DistributedStore store = OssDistributedStore.create(ossClient, "my-bucket", "agentscope/");

HarnessAgent agent = HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

## Components Provided

### 1. OssAgentStateStore

Agent state persisted to OSS objects.

### 2. OssBaseStore

Workspace filesystem KV storage to OSS objects.

### 3. OssSnapshotSpec

Sandbox snapshots to OSS — the best choice for large workspace archives.

### Not Provided: SandboxExecutionGuard

Object storage is unsuitable for distributed locking. Mix in a Redis guard:

```java
DistributedStore ossStore = OssDistributedStore.create(ossClient, "my-bucket", "agentscope/");

DistributedStore mixed = DistributedStore.builder()
    .agentStateStore(ossStore.agentStateStore())
    .baseStore(ossStore.baseStore())
    .sandboxSnapshotSpec(ossStore.sandboxSnapshotSpec())
    .sandboxExecutionGuard(RedisDistributedStore.fromJedis(jedis).sandboxExecutionGuard())
    .build();
```

## When to Use

| Scenario | Recommendation |
|----------|---------------|
| Large snapshots (>100MB workspaces) | **First choice**: OSS |
| Alibaba Cloud ecosystem | OSS |
| Need sandbox concurrency lock | Mix OSS + Redis |
| Lowest latency | Redis |

## Security

- Use RAM Role + STS temporary credentials in production — avoid hardcoded AK/SK
- Configure bucket lifecycle rules (e.g. 7-day auto-expiry) to control storage costs
