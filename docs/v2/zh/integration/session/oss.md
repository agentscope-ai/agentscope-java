```{note}
本页介绍的是**阿里云 OSS** 状态存储。除此之外还提供 AWS S3、腾讯云 COS 两种实现——完整对比见 [对象存储（OSS / S3 / COS）](../distributed/oss.md)；或直接查阅 `AwsS3AgentStateStore`（模块 `agentscope-extensions-oss-aws`）与 `TencentCosAgentStateStore`（模块 `agentscope-extensions-oss-tencent`）。
```

# 阿里云 OSS 状态存储

`agentscope-extensions-oss-aliyun` 把 AgentScope 的 Agent 状态持久化到阿里云对象存储（OSS）。适合大容量数据和阿里云生态的场景。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-oss-aliyun</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

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

## Key 结构

`(userId, sessionId)` 二元组会被打包进 OSS 对象路径：

| 类型 | Key 模式 |
| --- | --- |
| 单值 | `{keyPrefix}{userId}/{sessionId}/{stateKey}.json` |
| 列表 | `{keyPrefix}{userId}/{sessionId}/{stateKey}.list.json` |
| 列表 hash | `{keyPrefix}{userId}/{sessionId}/{stateKey}.list.hash`（变更检测用） |

匿名 session（`userId` 为 null）时 `userId` 用 `__anon__` 替代。

## Builder 配置参数

| 方法 | 说明 |
| --- | --- |
| `ossClient(OSS)` | 必填。阿里云 OSS 客户端 |
| `bucketName(String)` | 必填。OSS Bucket 名称 |
| `keyPrefix(String)` | 默认 `agentscope/state/` |

## 安全提示

- 生产环境建议使用 RAM Role + STS 临时凭证，避免在代码中硬编码 AK/SK
- 为 bucket 配置生命周期规则（如 7 天自动过期），避免存储成本失控
