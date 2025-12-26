# Nacos Registry Integration

AgentScope supports integration with the Nacos registry to provide automatic registration and discovery capabilities for [A2A](./a2a.md) protocols.

---

## A2A Registry

Using Nacos as an A2A registry allows AgentScope's A2A services to be automatically registered to Nacos, or automatically discover A2A services from Nacos for invocation.

### Automatically Discovering A2A Services from Nacos

#### Quick Start

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-a2a</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

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
NacosAgentCardResolver nacosAgentCardResolver = new NacosAgentCardResolver(a2aService);
// Create A2A Agent
A2aAgent agent = A2aAgent.builder()
        .name("remote-agent")
        .agentCardResolver(nacosAgentCardResolver)
        .build();
```

### Automatically Registering A2A Services to Nacos

#### Quick Start

First, refer to [A2A Server: A2A Server](./a2a.md#server-a2a-server) to create the exposed A2A service.

Then add the Nacos Registry implementation.

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-a2a</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

- For `Spring Boot approach`

```java
@Configuration
public class NacosAgentRegistryConfiguration {

    @Bean
    public AgentRegistry nacosAgentRegistry() {
        // Set Nacos address
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, "localhost:8848");
        // Create Nacos Client
        AiService aiService = AiFactory.createAiService(properties);
        // Create Nacos AgentRegistry
        return NacosAgentRegistry.builder(aiService).build();
    }
}
```

- For `manual creation approach`

```java
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

| Parameter                     | Type    | Description                                                                                   |
|------------------------------|---------|-----------------------------------------------------------------------------------------------|
| `setAsLatest`                | boolean | Always register the A2A service as the latest version, default is `false`.                    |
| `enabledRegisterEndpoint`    | boolean | Automatically register all `Transport` as Endpoints for this A2A service, default is `true`. When set to `false`, only Agent Card will be published. |
| `overwritePreferredTransport`| String  | When registering A2A services, use this `Transport` to override the `preferredTransport` and `url` in the Agent Card, default is `null`. |

---

## Additional Resources

- **Nacos Quick Start**: https://nacos.io/docs/latest/quickstart/quick-start
- **Nacos Java SDK**: https://nacos.io/docs/latest/manual/user/java-sdk/usage
- **Nacos Java SDK Additional Configuration Parameters**: https://nacos.io/docs/latest/manual/user/java-sdk/properties
- **Nacos Community**: https://github.com/alibaba/nacos