# S3-Compatible Object Storage

`agentscope-extensions-oss` provides distributed storage backed by S3-compatible object storage such as MinIO, AWS S3, or Alibaba Cloud OSS. It uses the AWS SDK `S3Client` against an S3-compatible endpoint, so it is a compatibility layer for S3-style object storage rather than a direct Alibaba Cloud OSS SDK wrapper.

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

OssDistributedStore store = OssDistributedStore.create(
    "http://localhost:9000",
    "minioadmin",
    "minioadmin",
    "my-bucket",
    "agentscope/");
```

## Components Provided

### 1. OssAgentStateStore

Agent state persisted to object storage objects.

### 2. OssBaseStore

Workspace filesystem KV storage to object storage objects.

### 3. OssSnapshotSpec

Sandbox snapshots stored in object storage.

## Security

- Use short-lived credentials in production when possible.
- Configure bucket lifecycle rules to control storage costs.
