# Nacos 注册中心集成

AgentScope 支持集成 Nacos注册中心，进行 [A2A](./a2a.md) 协议自动注册与发现能力。

---

## A2A 注册中心

使用 Nacos 作为 A2A 注册中心，将 AgentScope 所提供的 A2A 服务自动注册到 Nacos 中，或自动从 Nacos 中发现 A2A 服务进行调用。

### 从 Nacos 中自动发现 A2A 服务

#### 快速开始

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

// 设置 Nacos 地址
Properties properties = new Properties();
properties.put(PropertyKeyConst.SERVER_ADDR, "localhost:8848");
// 创建 Nacos Client
AiService aiService = AiFactory.createAiService(properties);
// 创建 Nacos 的 AgentCardResolver
NacosAgentCardResolver nacosAgentCardResolver = new NacosAgentCardResolver(a2aService);
// 创建 A2A Agent
A2aAgent agent = A2aAgent.builder()
        .name("remote-agent")
        .agentCardResolver(nacosAgentCardResolver)
        .build();
```

### 向 Nacos 中自动注册 A2A 服务

#### 快速开始

先参考 [A2A-服务端：A2A Server](./a2a.md#服务端a2a-server) 创建好暴露的 A2A 服务。

之后添加 Nacos 的 Registry实现。

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-a2a</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

- 为`Spring Boot 方式`添加

```java
@Configuration
public class NacosAgentRegistryConfiguration {

    @Bean
    public AgentRegistry nacosAgentRegistry() {
        // 设置 Nacos 地址
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, "localhost:8848");
        // 创建 Nacos Client
        AiService aiService = AiFactory.createAiService(properties);
        // 创建 Nacos 的 AgentRegistry
        return NacosAgentRegistry.builder(aiService).build();
    }
}
```

- 为`手动创建方式`添加

```java
// 设置 Nacos 地址
Properties properties = new Properties();
properties.put(PropertyKeyConst.SERVER_ADDR, "localhost:8848");
// 创建 Nacos Client
AiService aiService = AiFactory.createAiService(properties);
// 添加 Nacos 的 AgentRegistry
AgentScopeA2aServer server = AgentScopeA2aServer.builder(
        ReActAgent.builder()
            .name("my-assistant")
            .sysPrompt("你是一个有用的助手"))
    .deploymentProperties(DeploymentProperties.builder()
        .host("localhost")
        .port(8080)
        .build())
    .withAgentRegistry(NacosAgentRegistry.builder(aiService).build())
    .build();
```

#### 配置选项

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

| 参数                            | 类型      | 描述                                                                             |
|-------------------------------|---------|--------------------------------------------------------------------------------|
| `setAsLatest`                 | boolean | 注册的 A2A 服务始终为最新版本，默认为`false`。                                                  |
| `enabledRegisterEndpoint`     | boolean | 自动注册所有`Transport`作为此 A2A 服务的 Endpoint，默认为`true`，当设置为`false`时，仅会发布Agent Card。   |
| `overwritePreferredTransport` | String  | 注册 A2A 服务时，使用此`Transport`覆盖 Agent Card 中的`preferredTranspor`和`url`，默认为`false`。 |

--- 

## 更多资源

- **Nacos 快速开始** : https://nacos.io/docs/latest/quickstart/quick-start
- **Nacos Java SDK** : https://nacos.io/docs/latest/manual/user/java-sdk/usage
- **Nacos Java SDK 更多配置参数** : https://nacos.io/docs/latest/manual/user/java-sdk/properties
- **Nacos 社区** : https://github.com/alibaba/nacos