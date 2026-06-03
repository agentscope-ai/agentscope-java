# RocketMQ

`agentscope-extensions-rocketmq` provides a [RocketMQ](https://rocketmq.apache.org/)-based A2A transport: callers publish A2A requests to a RocketMQ topic, the Agent server consumes them, and responses (including streaming) are sent back over MQ.

## When to use

- The Agent service runs in a private network and shouldn't expose HTTP directly.
- Caller traffic needs queueing, replay, or audit.
- Multiple Agent services should load-balance via consumer groups.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rocketmq</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Server: expose an Agent over RocketMQ

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

// 1) Wrap the A2A transport with RocketMQ
TransportWrapper rocketMqTransport = RocketMQTransportWrapperBuilder.create(config)
    .agentBuilder(ReActAgent.builder().name("backend-agent").model(model))
    .build();

// 2) Register with AgentScopeA2aServer
AgentScopeA2aServer server = AgentScopeA2aServer.builder()
    .agentBuilder(ReActAgent.builder().name("backend-agent").model(model))
    .transportWrapper("ROCKETMQ", rocketMqTransport)
    .build();
```

After startup, the business topic (`bizTopic`) subscribes to A2A JSON-RPC requests; results are published to `replyTopic`.

## Client: invoke a remote Agent via RocketMQ

The caller uses the standard RocketMQ producer to publish a `RocketMQRequest`:

```java
// Caller business code
Producer producer = ...;        // RocketMQ Producer
producer.send(buildMessageForRequest(jsonRpcRequest));
// Consume RocketMQResponse from replyTopic
```

> Recommended: pair `agentscope-spring-boot-starter-a2a-server` with `agentscope-extensions-rocketmq` — the starter auto-registers the transport.

## Key configuration

| Field | Notes |
| --- | --- |
| `rocketMQEndpoint` | RocketMQ access endpoint |
| `rocketMQNamespace` | Namespace for multi-tenant isolation |
| `bizTopic` | Business request topic |
| `bizConsumerGroup` | Server-side consumer group |
| `replyTopic` | Topic for responses |
| `accessKey / secretKey` | Auth credentials |

## Streaming responses

When the server returns a stream, each chunk is wrapped as a `RocketMQResponse` and published to `replyTopic`. The caller correlates by `requestId` to reconstruct a `Flux<JSONRPCResponse>`.
