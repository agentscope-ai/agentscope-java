```{note}
This page documents the **Alibaba Cloud OSS** state store. AWS S3 and Tencent Cloud COS state stores are available too — see [Object Storage (OSS / S3 / COS)](../distributed/oss.md) for the full comparison, or jump straight to `AwsS3AgentStateStore` (module `agentscope-extensions-oss-aws`) or `TencentCosAgentStateStore` (module `agentscope-extensions-oss-tencent`).
```

# Alibaba Cloud OSS State Store

`agentscope-extensions-oss-aliyun` persists AgentScope agent state in Alibaba Cloud Object Storage Service (OSS). Ideal for large-capacity data and Alibaba Cloud ecosystems.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-oss-aliyun</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.oss.aliyun.AliyunOssAgentStateStore;

OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

AgentStateStore stateStore = AliyunOssAgentStateStore.builder()
    .ossClient(ossClient)
    .bucketName("my-agentscope-bucket")
    .keyPrefix("agentscope/state/")
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)
    .build();
```

## Key layout

The `(userId, sessionId)` pair is packed into OSS object paths:

| Type | Key pattern |
| --- | --- |
| Single value | `{keyPrefix}{userId}/{sessionId}/{stateKey}.json` |
| List | `{keyPrefix}{userId}/{sessionId}/{stateKey}.list.json` |
| List hash | `{keyPrefix}{userId}/{sessionId}/{stateKey}.list.hash` (change detection) |

Anonymous sessions (`userId` is null) use `__anon__` as the user segment.

## Builder reference

| Method | Notes |
| --- | --- |
| `ossClient(OSS)` | Required. Alibaba Cloud OSS client |
| `bucketName(String)` | Required. OSS bucket name |
| `keyPrefix(String)` | Default `agentscope/state/` |

## Security

- Use RAM Role + STS temporary credentials in production — avoid hardcoded AK/SK
- Configure bucket lifecycle rules (e.g. 7-day auto-expiry) to control storage costs
