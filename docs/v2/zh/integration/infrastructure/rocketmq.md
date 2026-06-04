# RocketMQ

`agentscope-extensions-rocketmq` 提供基于 [Apache RocketMQ](https://rocketmq.apache.org/) 的 A2A 传输层：调用方把 A2A 请求发到 RocketMQ topic，Agent 服务端从 topic 消费、处理后再通过 MQ 反向回应（含流式）。

## 何时使用

- Agent 服务部署在内网，不希望直接暴露 HTTP。
- 调用流量需要削峰填谷、回放、审计。
- 多个 Agent 服务希望以"消费组"形式做负载均衡。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rocketmq</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 服务端：把 Agent 暴露到 RocketMQ

```java
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.extensions.rocketmq.a2a.config.RocketMQA2aConfig;
import io.agentscope.extensions.rocketmq.a2a.wrapper.RocketMQTransportWrapperBuilder;

RocketMQA2aConfig config = new RocketMQA2aConfig();
config.setRocketMQEndpoint("localhost:8081");
config.setRocketMQNamespace("agentscope");
config.setBizTopic("a2a-request");
config.setBizConsumerGroup("a2a-server-cg");
config.setReplyTopic("a2a-reply");
config.setAccessKey("AK");
config.setSecretKey("SK");

// 1) 用 RocketMQ 包一层 A2A Transport
TransportWrapper rocketMqTransport = RocketMQTransportWrapperBuilder.create(config)
    .agentBuilder(ReActAgent.builder().name("backend-agent").model(model))
    .build();

// 2) 注册到 AgentScopeA2aServer
AgentScopeA2aServer server = AgentScopeA2aServer.builder()
    .agentBuilder(ReActAgent.builder().name("backend-agent").model(model))
    .transportWrapper("ROCKETMQ", rocketMqTransport)
    .build();
```

启动后，业务 topic（`bizTopic`）会订阅 A2A JSON-RPC 请求；处理结果通过 `replyTopic` 反向投递。

## 客户端：通过 RocketMQ 调用远端 Agent

调用方使用 RocketMQ 客户端发送 `RocketMQRequest`：

```java
// 调用方业务代码
Producer producer = ...;        // RocketMQ Producer
producer.send(buildMessageForRequest(jsonRpcRequest));
// 在 replyTopic 上消费 RocketMQResponse
```

> 推荐通过 `agentscope-spring-boot-starter-a2a-server` + `agentscope-extensions-rocketmq` 一并使用：starter 会把上面的 transport 自动注册到 server。

## 关键配置项

| 字段 | 说明 |
| --- | --- |
| `rocketMQEndpoint` | RocketMQ 接入地址 |
| `rocketMQNamespace` | 命名空间（多租户隔离） |
| `bizTopic` | 业务请求 topic |
| `bizConsumerGroup` | 服务端消费组 |
| `replyTopic` | 反向回应 topic |
| `accessKey / secretKey` | 鉴权凭证 |

## 流式响应

服务端返回流式时，每个增量都会被打包成 `RocketMQResponse` 投递到 `replyTopic`，调用方按 `requestId` 聚合即可得到 `Flux<JSONRPCResponse>`。
