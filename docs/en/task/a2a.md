# A2A (Agent2Agent)

A2A is AgentScope's support for the [A2A protocol](https://a2a-protocol.org/latest/specification/), including client (calling remote Agents) and server (exposing local Agents) components.

---

## Client: A2aAgent

Use remote A2A services as local Agents.

### Quick Start

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;

// Create A2A Agent
A2aAgent agent = A2aAgent.builder()
    .name("remote-agent")
    .agentCardResolver(new WellKnownAgentCardResolver(
        "http://127.0.0.1:8080",
        "/.well-known/agent-card.json",
        Map.of()))
    .build();

// Call remote Agent
Msg response = agent.call(userMsg).block();
```

### Configuration Options

| Parameter | Type | Description |
|-----------|------|-------------|
| `agentCard` | AgentCard | Provide AgentCard directly |
| `agentCardResolver` | AgentCardResolver | Obtain AgentCard through resolver |
| `memory` | Memory | Memory component |
| `hook` / `hooks` | Hook | Hook functions |

### AgentCard Resolution

```java
// Option 1: Provide directly
A2aAgent.builder()
    .agentCard(agentCard)
    .build();

// Option 2: From well-known path
A2aAgent.builder()
    .agentCardResolver(new WellKnownAgentCardResolver(url, path, headers))
    .build();

// Option 3: From Nacos 
A2aAgent.builder()
    .agentCardResolver(new NacosAgentCardResolver(nacosClient))
    .build();

// Option 4: Custom resolver
A2aAgent.builder()
    .agentCardResolver(agentName -> customGetAgentCard(agentName))
    .build();
```

#### Automatically Discovering A2A Services from Nacos

Using Nacos as an A2A registry allows AgentScope to automatically discover A2A services from Nacos for invocation.

```java
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;

// Set Nacos address
Properties properties = new Properties();
properties.put(PropertyKeyConst.SERVER_ADDR, "localhost:8848");
// Create Nacos Client
AiService aiService = AiFactory.createAiService(properties);
// Create Nacos AgentCardResolver
NacosAgentCardResolver nacosAgentCardResolver = new NacosAgentCardResolver(aiService);
// Create A2A Agent
A2aAgent agent = A2aAgent.builder()
        .name("remote-agent")
        .agentCardResolver(nacosAgentCardResolver)
        .build();
```

---

## Server: A2A Server

Expose local Agents as A2A services.

### Spring Boot (Recommended)

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-a2a-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```yaml
# application.yml
agentscope:
  dashscope:
    api-key: your-api-key
  agent:
    name: my-assistant
  a2a:
    server:
      enabled: true
      card:
        name: My Assistant
        description: An intelligent assistant based on AgentScope
```

```java
@SpringBootApplication
public class A2aServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(A2aServerApplication.class, args);
    }
}
```

### Manual Setup

```java
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;

// Create A2A Server
AgentScopeA2aServer server = AgentScopeA2aServer.builder(
        ReActAgent.builder()
            .name("my-assistant")
            .sysPrompt("You are a helpful assistant"))
    .deploymentProperties(DeploymentProperties.builder()
        .host("localhost")
        .port(8080)
        .build())
    .build();

// Get transport handler for web framework
JsonRpcTransportWrapper transport =
    server.getTransportWrapper("JSON-RPC", JsonRpcTransportWrapper.class);

// Call when web service is ready
server.postEndpointReady();
```

### Configure AgentCard

```java
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;

ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder()
    .name("My Assistant")
    .description("Intelligent assistant")
    .version("1.0.0")
    .skills(List.of(
        new AgentSkill("text-generation", "Text Generation"),
        new AgentSkill("question-answering", "Q&A")))
    .build();

AgentScopeA2aServer.builder(agentBuilder)
    .agentCard(agentCard)
    .build();
```

### Auto Register to Registry

Using Nacos as an A2A registry allows AgentScope's A2A services to be automatically registered to Nacos.

- For `Spring Boot approach` (Recommended)

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-a2a-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>

<!-- Additional dependency for Nacos Spring Boot starter -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-nacos-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```yaml
# application.yml
agentscope:
  dashscope:
    api-key: your-api-key
  agent:
    name: my-assistant
  a2a:
    server:
      enabled: true
      card:
        name: My Assistant
        description: An intelligent assistant based on AgentScope
    # Adding Nacos properties under `agentscope.a2a`
    nacos:
      server-addr: ${NACOS_SERVER_ADDRESS:127.0.0.1:8848}
      username: ${NACOS_USERNAME:nacos}
      password: ${NACOS_PASSWORD:nacos}
```

```java
@SpringBootApplication
public class A2aServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(A2aServerApplication.class, args);
    }
}
```

- For `manual creation approach`

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-a2a</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.nacos.a2a.registry.NacosAgentRegistry;

// Set Nacos address
Properties properties = new Properties();
properties.put(PropertyKeyConst.SERVER_ADDR, "localhost:8848");
// Create Nacos Client
AiService aiService = AiFactory.createAiService(properties);
// Add Nacos AgentRegistry
AgentScopeA2aServer server = AgentScopeA2aServer.builder(
        ReActAgent.builder()
            .name("my-assistant")
            .sysPrompt("You are a helpful assistant"))
    .deploymentProperties(DeploymentProperties.builder()
        .host("localhost")
        .port(8080)
        .build())
    .withAgentRegistry(NacosAgentRegistry.builder(aiService).build())
    .build();
```

#### Configuration Options

```java
NacosA2aRegistryProperties registryProperties = NacosA2aRegistryProperties.builder()
        .setAsLatest(true)
        .enabledRegisterEndpoint(true)
        .overwritePreferredTransport("http")
        .build();

NacosAgentRegistry agentRegistry = NacosAgentRegistry
        .builder(aiService)
        .nacosA2aProperties(registryProperties)
        .build();
```

| Parameter                     | Type    | Description                                                                                                                                          |
|-------------------------------|---------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| `setAsLatest`                 | boolean | Always register the A2A service as the latest version, default is `false`.                                                                           |
| `enabledRegisterEndpoint`     | boolean | Automatically register all `Transport` as Endpoints for this A2A service, default is `true`. When set to `false`, only Agent Card will be published. |
| `overwritePreferredTransport` | String  | When registering A2A services, use this `Transport` to override the `preferredTransport` and `url` in the Agent Card, default is `null`.             |

---

## Task Interruption

```java
// Client interruption
agent.interrupt();

// Interrupt with message
agent.interrupt(Msg.builder()
    .textContent("User cancelled the operation")
    .build());
```

---
## Achieving Highly Reliable Asynchronous Communication with Apache RocketMQ
Note: Apache RocketMQ must be deployed with either an open-source version or a commercial edition that supports the lightweight consumption model LiteTopic(The open-source version is expected to be released in January — stay tuned!)

### Configure the client to use Apache RocketMQ as the communication channel

```xml
<!-- Add the dependency in pom.xml -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-a2a</artifactId>
    <version>${RELEASE.VERSION}</version>
</dependency>
```
The client uses Apache RocketMQ to build A2aAgent

```java
//Construct a RocketMQTransportConfig object to configure RocketMQTransport.
RocketMQTransportConfig rocketMQTransportConfig = new RocketMQTransportConfig();
//Configure the Apache RocketMQ account
rocketMQTransportConfig.setAccessKey(accessKey);
//Configure the Apache RocketMQ password
rocketMQTransportConfig.setSecretKey(secretKey);
//Configure a LiteTopic to receive response messages
rocketMQTransportConfig.setWorkAgentResponseTopic(workAgentResponseTopic);
//Configure a consumer that subscribes to the LiteTopic
rocketMQTransportConfig.setWorkAgentResponseGroupID(workAgentResponseGroupID);
//Configure the namespace for Apache RocketMQ
rocketMQTransportConfig.setRocketMQNamespace(rocketMQNamespace);
rocketMQTransportConfig.setHttpClient(new JdkA2AHttpClient());
//Build A2aAgentConfig using RocketMQTransport and rocketMQTransportConfig
A2aAgentConfig a2aAgentConfig = new A2aAgentConfigBuilder().withTransport(RocketMQTransport.class, rocketMQTransportConfig).build();
//Parse the corresponding Agent service and build an A2aAgent
A2aAgent agent = A2aAgent.builder().a2aAgentConfig(a2aAgentConfig).name(AGENT_NAME).agentCardResolver(WellKnownAgentCardResolver.builder().baseUrl("http://127.0.0.1:10001").build()).build();
```

| Parameter | Type | Description | Required |
|-----------|------|-----------|----|
| `accessKey` | String | RocketMQ AccessKey | NO |
| `secretKey` | String | RocketMQ SecretKey | NO |
| `workAgentResponseTopic` | String | LiteTopic | YES |
| `workAgentResponseGroupID` | String | The CID of the consumer subscribed to LiteTopic | YES |
| `rocketMQNamespace` | String | RocketMQ Namespace | NO |

### The server exposes an Agent service externally over the Apache RocketMQ communication protocol.

Construct the URL for the Apache RocketMQ communication protocol.

```java
private static String buildRocketMQUrl(String rocketMQEndpoint, String rocketMQNamespace, String bizTopic) {
    if (StringUtils.isEmpty(rocketMQEndpoint) || StringUtils.isEmpty(bizTopic)) {
        throw new IllegalArgumentException(
            "Invalid parameters for building RocketMQ URL: 'rocketMQEndpoint' and 'bizTopic' must not be empty. Please check your RocketMQ configuration."
        );
    }
    return "http://" + rocketMQEndpoint + "/" + rocketMQNamespace + "/" + bizTopic;
}
```

| Parameter | Type | Description | Required |
|-----------|------|-------------|----|
| `rocketmqEndpoint` | String | RocketMQ Endpoint | Yes |
| `rocketMQNamespace` | String | RocketMQ Namespace | NO |
| `bizTopic` | String | Normal Topic | Yes |

The server externally exposes the AgentCard service

```java
//Externally expose the AgentCard service based on RocketMQ communication
AgentInterface agentInterface = new AgentInterface(RocketMQA2AConstant.ROCKETMQ_PROTOCOL, buildRocketMQUrl());
ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder()
    .url(buildRocketMQUrl())
    .preferredTransport(RocketMQA2AConstant.ROCKETMQ_PROTOCOL)
    .additionalInterfaces(List.of(agentInterface))
    .description("An intelligent assistant enabling highly reliable asynchronous communication based on Apache RocketMQ.")
    .build();
//Configure the DASHSCOPE_API_KEY to invoke the LLM service
AgentApp agentApp = new AgentApp(agent(agentBuilder(dashScopeChatModel(DASHSCOPE_API_KEY))));
agentApp.deployManager(LocalDeployManager.builder()
    .protocolConfigs(List.of(new A2aProtocolConfig(agentCard, 60, 10)))
    .port(10001)
    .build());
```
```java
//Build a DashScopeChatModel to invoke the LLM service.
private static DashScopeChatModel dashScopeChatModel(String dashScopeApiKey) {
    if (StringUtils.isEmpty(dashScopeApiKey)) {
        throw new IllegalArgumentException(
            "DashScope API key is empty. Please set the environment variable `AI_DASHSCOPE_API_KEY`."
        );
    }
    return DashScopeChatModel.builder()
        .apiKey(dashScopeApiKey)
        .modelName("qwen-max")
        .stream(true)
        .enableThinking(true)
        .build();
}
```

```java
//Build ReActAgent.Builder
private static ReActAgent.Builder agentBuilder(DashScopeChatModel model) {
    return ReActAgent.builder().model(model).name(AGENT_NAME).sysPrompt("You are an example implementation of the A2A (Agent-to-Agent) protocol using RocketMQTransport. You can answer simple questions based on your internal knowledge.");
}
```
```java
//Build AgentScopeAgentHandler
private static AgentScopeAgentHandler agent(ReActAssistant.Builder builder) {
    return new AgentScopeAgentHandler() {
        @Override
        public boolean isHealthy() {
            return true;
        }
        @Override
        public Flux<?> streamQuery(AgentRequest request, Object messages) {
            ReActAgent agent = builder.build();
            StreamOptions streamOptions = StreamOptions.builder()
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                .incremental(true)
                .build();

            if (messages instanceof List<?>) {
                return agent.stream((List<Msg>) messages, streamOptions);
            } else if (messages instanceof Msg) {
                return agent.stream((Msg) messages, streamOptions);
            } else {
                Msg msg = Msg.builder().role(MsgRole.USER).build();
                return agent.stream(msg, streamOptions);
            }
        }

        @Override
        public String getName() {
            return builder.build().getName();
        }

        @Override
        public String getDescription() {
            return builder.build().getDescription();
        }
    };
}
```
---

## More Resources

- **A2A Protocol Specification**: https://a2a-protocol.org/latest/specification/
- **Agent Interface**: [Agent.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-core/src/main/java/io/agentscope/core/agent/Agent.java)
- **Nacos Quick Start**: https://nacos.io/docs/latest/quickstart/quick-start
- **Nacos Java SDK**: https://nacos.io/docs/latest/manual/user/java-sdk/usage
- **Nacos Java SDK Additional Configuration Parameters**: https://nacos.io/docs/latest/manual/user/java-sdk/properties
- **Nacos Community**: https://github.com/alibaba/nacos
- **A demonstration of an AgentScope intelligent agent application based on Apache RocketMQ**: https://github.com/agentscope-ai/agentscope-runtime-java/tree/main/examples/simple_agent_use_rocketmq_example
- **Apache RocketMQ Community** : https://github.com/apache/rocketmq
- **Apache RocketMQ A2A Asynchronous Communication Component** : https://github.com/apache/rocketmq-a2a
