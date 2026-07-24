# Object Storage (OSS / S3 / COS)

`agentscope-extensions-oss` is now a multi-vendor aggregate. It defines an SDK-independent base and ships three vendor implementations backed by Alibaba Cloud OSS, AWS S3, and Tencent Cloud COS. All three expose the same `DistributedStore` capability — pick the one that fits your infrastructure.

## Module Layout

| Module | Purpose | SDK |
|--------|---------|-----|
| `agentscope-extensions-oss-base` | SDK-independent abstractions (`OssAdapter`, `AbstractOssBaseStore`, ...) | none |
| `agentscope-extensions-oss-aliyun` | Alibaba Cloud OSS implementation | `com.aliyun.oss:aliyun-sdk-oss` |
| `agentscope-extensions-oss-aws` | AWS S3 implementation | `software.amazon.awssdk:s3` |
| `agentscope-extensions-oss-tencent` | Tencent Cloud COS implementation | `com.qcloud:cos_api` |

You only depend on the vendor module you need; `oss-base` is pulled in transitively.

## Dependency

Pick exactly one vendor module:

```xml
<!-- Alibaba Cloud OSS -->
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

<!-- Tencent Cloud COS -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-oss-tencent</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## One-Line Setup

### Alibaba Cloud OSS

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

### Tencent Cloud COS

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

Then wire it into your agent:

```java
HarnessAgent agent = HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
    .build();
```

## Components Provided

Each vendor module exposes the same three components with vendor-prefixed names (`Aliyun*`, `AwsS3*`, `TencentCos*`):

- **`{Vendor}AgentStateStore`** — Agent state persisted to object storage.
- **`{Vendor}BaseStore`** — Workspace filesystem KV storage.
- **`{Vendor}SnapshotSpec`** — Sandbox snapshots; the best choice for large workspace archives.

### Not Provided: SandboxExecutionGuard

Object storage is unsuitable for distributed locking. Mix in a Redis guard:

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

The example uses Alibaba Cloud; the same pattern applies to `AwsS3DistributedStore` and `TencentCosDistributedStore`.

## When to Use

| Scenario | Recommendation |
|----------|---------------|
| Large snapshots (>100MB workspaces) | **First choice**: any object storage vendor |
| Running on Alibaba Cloud | `agentscope-extensions-oss-aliyun` |
| Running on AWS | `agentscope-extensions-oss-aws` |
| Running on Tencent Cloud | `agentscope-extensions-oss-tencent` |
| Need sandbox concurrency lock | Mix any OSS vendor + Redis |
| Lowest latency | Redis |

## Security

- Use RAM Role + STS (Aliyun), IAM Role + STS (AWS), or CAM Role + STS (Tencent) temporary credentials in production — avoid hardcoded AK/SK.
- Configure bucket lifecycle rules (e.g. 7-day auto-expiry) to control storage costs.
