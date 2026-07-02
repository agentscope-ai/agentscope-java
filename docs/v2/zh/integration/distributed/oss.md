# 对象存储（OSS / S3 / COS）

`agentscope-extensions-oss` 已经拆成多厂商聚合模块：一层 SDK 无关的 base，加上阿里云 OSS、AWS S3、腾讯云 COS 三家实现。三家都对外暴露相同的 `DistributedStore` 能力，按你的基础设施挑一家即可。

## 模块结构

| 模块 | 作用 | 依赖 SDK |
|--------|---------|-----|
| `agentscope-extensions-oss-base` | SDK 无关抽象（`OssAdapter`、`AbstractOssBaseStore` 等） | 无 |
| `agentscope-extensions-oss-aliyun` | 阿里云 OSS 实现 | `com.aliyun.oss:aliyun-sdk-oss` |
| `agentscope-extensions-oss-aws` | AWS S3 实现 | `software.amazon.awssdk:s3` |
| `agentscope-extensions-oss-tencent` | 腾讯云 COS 实现 | `com.qcloud:cos_api` |

只需要依赖对应厂商模块即可，`oss-base` 会被自动传递依赖引入。

## 依赖

按需选一家：

```xml
<!-- 阿里云 OSS -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-oss-aliyun</artifactId>
    <version>${agentscope.version}</version>
</dependency>

<!-- AWS S3 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-oss-aws</artifactId>
    <version>${agentscope.version}</version>
</dependency>

<!-- 腾讯云 COS -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-oss-tencent</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 一键配置

### 阿里云 OSS

```java
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import io.agentscope.extensions.oss.aliyun.AliyunOssDistributedStore;

OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
DistributedStore store =
        AliyunOssDistributedStore.create(ossClient, "my-bucket", "agentscope/");
```

### AWS S3

```java
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import io.agentscope.extensions.oss.aws.AwsS3DistributedStore;

S3Client s3 = S3Client.builder().region(Region.US_EAST_1).build();
DistributedStore store =
        AwsS3DistributedStore.create(s3, "my-bucket", "agentscope/");
```

### 腾讯云 COS

```java
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.region.Region;
import io.agentscope.extensions.oss.tencent.TencentCosDistributedStore;

COSClient cosClient = new COSClient(
        new BasicCOSCredentials(secretId, secretKey),
        new ClientConfig(new Region("ap-guangzhou")));
DistributedStore store =
        TencentCosDistributedStore.create(cosClient, "my-bucket-1250000000", "agentscope/");
```

装到 agent 上：

```java
HarnessAgent agent = HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

## 提供的组件

每家厂商模块都暴露相同的三个组件，命名带厂商前缀（`Aliyun*` / `AwsS3*` / `TencentCos*`）：

- **`{Vendor}AgentStateStore`** — Agent 状态持久化到对象存储。
- **`{Vendor}BaseStore`** — 工作区文件系统 KV 存储。
- **`{Vendor}SnapshotSpec`** — 沙箱快照；大工作区归档的首选。

### 不提供：SandboxExecutionGuard

对象存储不适合做分布式锁。如需 sandbox 并发控制，混合 Redis：

```java
DistributedStore ossStore =
        AliyunOssDistributedStore.create(ossClient, "my-bucket", "agentscope/");

DistributedStore mixed = DistributedStore.builder()
    .agentStateStore(ossStore.agentStateStore())
    .baseStore(ossStore.baseStore())
    .sandboxSnapshotSpec(ossStore.sandboxSnapshotSpec())
    .sandboxExecutionGuard(RedisDistributedStore.fromJedis(jedis).sandboxExecutionGuard())
    .build();
```

示例用的是阿里云；同样的写法适用于 `AwsS3DistributedStore` 和 `TencentCosDistributedStore`。

## 选型建议

| 场景 | 建议 |
|------|------|
| 大容量快照（>100MB 工作区） | **首选**任一对象存储厂商 |
| 运行在阿里云 | `agentscope-extensions-oss-aliyun` |
| 运行在 AWS | `agentscope-extensions-oss-aws` |
| 运行在腾讯云 | `agentscope-extensions-oss-tencent` |
| 需要 sandbox 并发锁 | 任一 OSS + Redis 混合 |
| 追求低延迟 | Redis |

## 安全提示

- 生产环境使用 RAM Role + STS（阿里云）/ IAM Role + STS（AWS）/ CAM Role + STS（腾讯云）临时凭证，避免硬编码 AK/SK。
- 为快照 bucket 配置生命周期规则（如 7 天自动过期），避免存储成本失控。
