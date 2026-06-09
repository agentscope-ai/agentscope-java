# 阿里云 OSS

`agentscope-extensions-oss` 提供基于阿里云对象存储（OSS）的分布式存储实现，适合大容量数据和阿里云生态的场景。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-oss</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 一键配置

```java
import io.agentscope.extensions.oss.OssDistributedStore;
import com.aliyun.oss.OSSClientBuilder;

OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
DistributedStore store = OssDistributedStore.create(ossClient, "my-bucket", "agentscope/");

HarnessAgent agent = HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

## 提供的组件

### 1. OssAgentStateStore

Agent 状态持久化到 OSS 对象。

```java
import io.agentscope.extensions.oss.OssAgentStateStore;

AgentStateStore store = OssAgentStateStore.builder()
    .ossClient(ossClient)
    .bucketName("my-bucket")
    .keyPrefix("agentscope/state/")
    .build();
```

### 2. OssBaseStore

工作区文件系统 KV 存储到 OSS 对象。

```java
import io.agentscope.extensions.oss.OssBaseStore;

BaseStore store = OssBaseStore.builder()
    .ossClient(ossClient)
    .bucketName("my-bucket")
    .keyPrefix("agentscope/store/")
    .build();
```

### 3. OssSnapshotSpec

沙箱快照存储到 OSS 对象。大工作区快照的首选——对象存储天然适合大二进制文件。

```java
import io.agentscope.extensions.oss.OssSnapshotSpec;

SandboxSnapshotSpec spec = new OssSnapshotSpec(ossClient, "my-bucket", "agentscope/snapshot/");

// 或者从 endpoint + AK/SK 直接构造
SandboxSnapshotSpec spec = new OssSnapshotSpec(
    "oss-cn-hangzhou.aliyuncs.com",
    accessKeyId, accessKeySecret,
    "my-bucket", "agentscope/snapshot/");
```

### 不提供：SandboxExecutionGuard

对象存储不适合做分布式锁。如需 sandbox 并发控制，混合 Redis store：

```java
DistributedStore ossStore = OssDistributedStore.create(ossClient, "my-bucket", "agentscope/");

DistributedStore mixed = DistributedStore.builder()
    .agentStateStore(ossStore.agentStateStore())
    .baseStore(ossStore.baseStore())
    .sandboxSnapshotSpec(ossStore.sandboxSnapshotSpec())
    .sandboxExecutionGuard(RedisDistributedStore.fromJedis(jedis).sandboxExecutionGuard())
    .build();
```

## 选型建议

| 场景 | 建议 |
|------|------|
| 大容量快照（>100MB 工作区） | **首选** OSS |
| 阿里云生态，已有 OSS bucket | OSS |
| 需要 sandbox 并发锁 | 混合 OSS + Redis |
| 追求低延迟 | Redis |

## 安全提示

- 生产环境建议使用 RAM Role + STS 临时凭证，避免在代码中硬编码 AK/SK
- 为快照 bucket 配置生命周期规则（如 7 天自动过期），避免存储成本失控
