```{note}
This page has been superseded by [Distributed Storage - S3-Compatible Object Storage](../distributed/oss.md). Content below is kept for reference.
```

# Object Storage State Store

`agentscope-extensions-oss` persists AgentScope agent state in S3-compatible object storage.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-oss</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.oss.OssAgentStateStore;
import software.amazon.awssdk.services.s3.S3Client;

S3Client s3Client = S3Client.builder().build();

AgentStateStore stateStore = OssAgentStateStore.builder()
    .s3Client(s3Client)
    .bucketName("my-agentscope-bucket")
    .keyPrefix("agentscope/state/")
    .build();
```

## Builder reference

| Method | Notes |
| --- | --- |
| `s3Client(S3Client)` | Required. S3-compatible client |
| `ossClient(S3Client)` | Backwards-compatible alias |
| `bucketName(String)` | Required. bucket name |
| `keyPrefix(String)` | Default `agentscope/state/` |
