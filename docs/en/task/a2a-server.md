# A2A (Agent2Agent) Server

## Overview

A2A (Agent2Agent) Server is an extension module in the AgentScope framework that exports local Agents as remote services following the [A2A specification](https://a2a-protocol.org/latest/specification/). Through this module, developers can easily expose their Agents in a standardized way to other clients or Agents that follow the A2A protocol.

The A2A Server is primarily responsible for:
- Packaging local Agents into service endpoints compliant with the A2A protocol
- Processing requests from remote clients and converting them into message formats understandable by local Agents
- Managing task lifecycle, status tracking, and event notifications
- Providing standard [AgentCard](https://a2a-protocol.org/latest/specification/#441-agentcard) metadata descriptions

## Core Concepts

### AgentRunner

`AgentRunner` is the core interface in the A2A Server for running and managing Agent instances. It provides methods for starting, stopping, and obtaining Agent messages, serving as a crucial bridge connecting local Agents with the A2A protocol.

AgentScope provides default implementations:
1. `ReActAgentWithBuilderRunner` - Uses [ReActAgent.Builder](../../../../agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java) to build ReActAgent instances
2. `ReActAgentWithStarterRunner` - Directly uses existing ReActAgent instances

### TransportWrapper

`TransportWrapper` encapsulates different transport protocols for handling requests and responses of specific protocols. Currently, it mainly supports the JSON-RPC protocol.

### AgentCard

[AgentCard](https://a2a-protocol.org/latest/specification/#441-agentcard) is one of the core concepts in the A2A protocol. It is a metadata description file for server-side Agents containing the following key information:
- Agent name and description
- Supported features and skills
- Communication protocols and address information
- Security authentication methods, etc.

In A2A Server, these attributes can be configured through [ConfigurableAgentCard](../../../agentscope-extensions/agentscope-extensions-a2a/agentscope-extensions-a2a-server/src/main/java/io/agentscope/core/a2a/server/card/ConfigurableAgentCard.java).

### Request Processing Flow

The basic request processing flow of A2A Server is as follows:
1. Client sends a request to the specified transport protocol endpoint (e.g., JSON-RPC)
2. TransportWrapper receives and parses the request
3. AgentScopeA2aRequestHandler converts the request into a format processable by the local Agent
4. AgentRunner invokes the actual Agent instance to process the request
5. The Agent's response is returned to the client through the layers

## Quick Start

Using Spring Boot Starter is the easiest way to get started. Simply add dependencies to your project and perform simple configuration:

```xml
<!-- pom.xml -->

<!-- spring.boot.version needs to be greater than 3.4 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>${spring.boot.version}</version>
</dependency>
<!-- agentscope.version needs to be greater than 1.0.3 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-a2a-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```yaml
# application.yml
server:
  port: 8080
  
agentscope:
  # Can be replaced with other model implementations, such as openai
  dashscope:
    api-key: your-dashscope-api-key
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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class A2aServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(A2aServerApplication.class, args);
    }
}
```

This completes a basic A2A Server configuration. Spring Boot will automatically create web endpoints and handle A2A requests.

If you don't want to use Spring Boot, you can also manually create an A2A Server:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;

// 1. Create ReActAgent
ReActAgent.Builder agentBuilder = ReActAgent.builder()
    .name("my-assistant")
    .sysPrompt("You are a helpful assistant");

// 2. Build A2A Server
DeploymentProperties deploymentProperties = new DeploymentProperties.Builder()
    .host("localhost") // Can be replaced with your host address, if not provided, the first non-loopback address of the local machine will be used as host
    .port(8080) // Required, the port number exposed by the web container
    .build();

AgentScopeA2aServer a2aServer = AgentScopeA2aServer.builder(agentBuilder)
    .deploymentProperties(deploymentProperties)
    .build();

// 3. Export endpoints in the web framework (using Spring Boot as an example)
// Get JSON-RPC transport handler, use it to process requests in your web framework
JsonRpcTransportWrapper jsonRpcTransport = 
    a2aServer.getTransportWrapper("JSON-RPC", JsonRpcTransportWrapper.class);

// Note: You need to create the web container and controller yourself to handle requests
// It is recommended to use agentscope-a2a-spring-boot-starter, which will help you create these components

// 4. Call when the web service is ready
a2aServer.postEndpointReady();
```

## Configuration and Usage

### Basic Configuration

To create an A2A Server, you need to use `AgentScopeA2aServer.Builder` to build an instance:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;

// Method 1: Using ReActAgent.Builder
ReActAgent.Builder agentBuilder = ReActAgent.builder()
    .name("my-assistant")
    .sysPrompt("You are a helpful assistant");

AgentScopeA2aServer server1 = AgentScopeA2aServer.builder(agentBuilder)
    .deploymentProperties(DeploymentProperties.builder()
        .host("localhost")
        .port(8080)
        .build())
    .build();

// Method 2: Using custom AgentRunner
CustomAgentRunner customRunner = new CustomAgentRunner();
AgentScopeA2aServer server2 = AgentScopeA2aServer.builder(customRunner)
    .build();
```

### Builder Parameters Explained

The A2A Server builder supports the following configuration parameters:

| Parameter               | Type                        | Description                              |
|------------------------|-----------------------------|------------------------------------------|
| `agentCard`            | ConfigurableAgentCard       | Custom AgentCard configuration           |
| `withTransport`        | TransportProperties         | Add supported transport protocols        |
| `taskStore`            | TaskStore                   | Task storage implementation, uses in-memory storage by default |
| `queueManager`         | QueueManager                | Queue manager, uses in-memory queue by default |
| `pushConfigStore`      | PushNotificationConfigStore | Push notification config storage, uses in-memory storage by default |
| `pushSender`           | PushNotificationSender      | Push notification sender, uses base push sender by default |
| `executor`             | Executor                    | Executor, uses CachedThreadPool by default |
| `deploymentProperties` | DeploymentProperties        | Deployment properties, used to generate default transport interface information |
| `withAgentRegistry`    | AgentRegistry               | Add Agent registry implementation for service registration |

Note: Transport protocol endpoints (such as web containers, routes, or controllers) need to be created by developers themselves. It is recommended to use `agentscope-a2a-spring-boot-starter`, which will automatically create web containers and related controllers.

### ConfigurableAgentCard Configuration

AgentCard metadata can be configured through ConfigurableAgentCard:

```java
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;

ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder()
    .name("My Assistant")
    .description("An intelligent assistant based on AgentScope")
    .version("1.0.0")
    .provider(new AgentProvider("AgentScope", "https://agentscope.io"))
    .documentationUrl("https://docs.agentscope.io/a2a-server")
    .skills(Arrays.asList(
        new AgentSkill("text-generation", "Text Generation"),
        new AgentSkill("question-answering", "Question Answering")))
    .build();

AgentScopeA2aServer server = AgentScopeA2aServer.builder(agentBuilder)
    .agentCard(agentCard)
    .build();
```

### Integration with Spring Boot

When using Spring Boot, AgentScope provides auto-configuration functionality to simplify A2A Server deployment:

```yaml
# application.yml
agentscope:
  a2a:
    server:
      enabled: true
      card:
        name: My Spring Boot Assistant
        description: A2A Server automatically configured through Spring Boot
        version: 1.0.0
```

The related auto-configuration class [AgentscopeA2aAutoConfiguration](../../../agentscope-extensions/agentscope-spring-boot-starters/agentscope-a2a-spring-boot-starter/src/main/java/io/agentscope/spring/boot/a2a/AgentscopeA2aAutoConfiguration.java) will automatically create the required beans and expose corresponding endpoints.

## Advanced Usage Examples

### Custom AgentRunner

```java
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Flux;

public class CustomAgentRunner implements AgentRunner {
    
    @Override
    public Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options) {
        // Custom Agent processing logic
        // Pre-processing, post-processing, etc. can be added here
        
        // Invoke the actual Agent implementation
        return actualAgent.call(messages);
    }
    
    @Override
    public void stop(String taskId) {
        // Interrupt the execution of the specified task
    }
    
    @Override
    public String getAgentName() {
        return "Custom Agent";
    }
    
    @Override
    public String getAgentDescription() {
        return "Custom Agent Implementation";
    }
}

// Using custom AgentRunner
AgentScopeA2aServer server = AgentScopeA2aServer.builder(new CustomAgentRunner())
    .build();
```

## Extension Capabilities

### Adding Custom AgentRegistry

AgentScope A2A Server allows adding custom AgentRegistry to implement automatic registration of Agents to A2A registries.

```java
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;

public class CustomAgentRegistry implements AgentRegistry {
    
    @Override
    public void register(AgentCard agentCard, Set<TransportProperties> transports) {
        // Custom registration logic, such as registering to external service discovery systems
        System.out.println("Registering agent: " + agentCard.name());
    }
}

// Using custom registry
AgentScopeA2aServer server = AgentScopeA2aServer.builder(agentBuilder)
    .withAgentRegistry(new CustomAgentRegistry())
    .build();

// Trigger registration when the service is ready
server.postEndpointReady();
```

### Configuring Multiple Transport Protocols

A2A Server allows adding multiple transport protocols to support different transmission methods. When adding other transport protocols, the following extension mechanism needs to be implemented:

#### 1. Build TransportWrapper and TransportWrapperBuilder

```java
import io.agentscope.core.a2a.server.transport.TransportWrapper;
import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.spec.SendMessageRequest;

public class CustomTransportWrapper implements TransportWrapper<CustomRequest, CustomResponse> {
    
    private final RequestHandler requestHandler;
    
    public CustomTransportWrapper(RequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }
    
    @Override
    public String getTransportType() {
        return "CUSTOM_PROTOCOL";
    }
    
    public CustomResponse handleRequest(
            CustomRequest body, Map<String, String> headers, Map<String, Object> metadata) {
        // Parse CustomRequest into A2A standard Request, such as SendMessageRequest.
        SendMessageRequest result = converRequest(body);
        Object result = requestHandler.handle(result);
        // Convert A2A standard response body to custom transport protocol response body.
        return converResponse(result);
    }
}
```

```java
import io.agentscope.core.a2a.server.transport.TransportWrapperBuilder;
import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import java.util.concurrent.Executor;

public class CustomTransportWrapperBuilder implements TransportWrapperBuilder<CustomTransportWrapper> {
    
    @Override
    public String getTransportType() {
        return "CUSTOM_PROTOCOL";
    }
    
    @Override
    public JsonRpcTransportWrapper build(
            AgentCard agentCard,
            RequestHandler requestHandler,
            Executor executor,
            AgentCard extendedAgentCard) {
        // Building parameters will be passed in. Currently, the necessary parameters mainly include AgentCard, A2aRequest handler, Executor thread pool, and extended AgentCard
        return new CustomTransportWrapper(requestHandler);
    }
}
```

#### 2. Place CustomTransportWrapperBuilder as an SPI implementation under resources

```shell
mkdir -p path_to_your_project/src/main/resources/META-INF/services/
cd path_to_your_project/src/main/resources/META-INF/services/
echo "CustomTransportWrapperBuilder.class.getCanonicalName" > io.agentscope.core.a2a.server.transport.TransportWrapperBuilder
# For example: echo "io.agentscope.demo.CustomTransportWrapperBuilder" > io.agentscope.core.a2a.server.transport.TransportWrapperBuilder
```

#### 3. Configure Transport Protocol

```java
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.a2a.spec.TransportProtocol;

AgentScopeA2aServer server = AgentScopeA2aServer.builder(agentBuilder)
    .withTransport(TransportProperties.builder(TransportProtocol.JSONRPC.asString())
        .host("localhost")
        .port(8080)
        .path("/jsonrpc")
        .build())
    .withTransport(TransportProperties.builder("CUSTOM_PROTOCOL")
        .host("localhost")
        .port(8081)
        .build())
    .build();
```

## Error Handling and Monitoring

### Exception Handling

A2A Server integrates a comprehensive exception handling mechanism that captures and appropriately handles exceptions at various processing stages, ensuring system stability.

### Task Monitoring

Task execution status can be monitored through TaskStore and QueueManager:

```java
// Get task status
TaskStore taskStore = server.getTaskStore(); // Needs to be obtained through reflection or custom methods
Task task = taskStore.getTask(taskId);
TaskStatus status = task.getStatus();
```

## Best Practices

1. **Recommend using Spring Boot Starter**: For most application scenarios, it is recommended to use `agentscope-a2a-spring-boot-starter`, which can automatically handle the creation of web containers and endpoints.

2. **Properly configure thread pools**: Configure appropriate executors based on actual load conditions to avoid resource waste or performance bottlenecks.

3. **Choose appropriate storage implementations**: For production environments, it is recommended to use persistent TaskStore and QueueManager implementations instead of default in-memory implementations.

4. **Security configuration**: Configure appropriate security schemes through AgentCard to protect services from unauthorized access.

5. **Monitoring and logging**: Enable detailed logging to facilitate troubleshooting and performance optimization.

6. **Reasonable timeout settings**: Set appropriate task timeout periods based on business requirements to avoid long-term blocking.