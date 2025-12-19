# A2A (Agent2Agent) Server

## 概述

A2A (Agent2Agent) Server 是 AgentScope 框架中的一个扩展模块，用于将本地的 Agent 导出为遵循 [A2A 规范](https://a2a-protocol.org/latest/specification/) 的远程服务。通过该模块，开发者可以轻松地将自己的 Agent 以标准化的方式暴露给其他遵循 A2A 协议的客户端或其他 Agent 使用。

A2A Server 主要负责：
- 将本地 Agent 包装成符合 A2A 协议的服务端点
- 处理来自远程客户端的请求并将其转换为本地 Agent 可理解的消息格式
- 管理任务生命周期、状态跟踪和事件通知
- 提供标准的 [AgentCard](https://a2a-protocol.org/latest/specification/#441-agentcard) 元数据描述

## 核心概念

### AgentRunner

`AgentRunner` 是 A2A Server 中的核心接口，用于运行和管理 Agent 实例。它提供了启动、停止和获取 Agent 消息的方法，是连接本地 Agent 和 A2A 协议的重要桥梁。

AgentScope 提供了默认的实现：
1. `ReActAgentWithBuilderRunner` - 使用 [ReActAgent.Builder](../../../../agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java) 构建 ReActAgent 实例
2. `ReActAgentWithStarterRunner` - 直接使用已有的 ReActAgent 实例

### TransportWrapper

`TransportWrapper` 是对不同传输协议的封装，用于处理特定协议的请求和响应。目前主要支持 JSON-RPC 协议。

### AgentCard

[AgentCard](https://a2a-protocol.org/latest/specification/#441-agentcard) 是 A2A 协议中的核心概念之一，它是服务端 Agent 的元数据描述文件，包含以下关键信息：
- Agent 名称和描述
- 支持的功能和技能
- 通信协议和地址信息
- 安全认证方式等

在 A2A Server 中，可以通过 [ConfigurableAgentCard](../../../agentscope-extensions/agentscope-extensions-a2a/agentscope-extensions-a2a-server/src/main/java/io/agentscope/core/a2a/server/card/ConfigurableAgentCard.java) 来配置这些属性。

### 请求处理流程

A2A Server 处理请求的基本流程如下：
1. 客户端发送请求到指定的传输协议端点（如 JSON-RPC）
2. TransportWrapper 接收并解析请求
3. AgentScopeA2aRequestHandler 将请求转换为本地 Agent 可处理的格式
4. AgentRunner 调用实际的 Agent 实例处理请求
5. Agent 的响应被逐层返回给客户端

## 快速开始

使用 Spring Boot Starter 是最简单的开始方式。只需要在项目中添加依赖并进行简单配置即可：

```xml
<!-- pom.xml -->

<!-- spring.boot.version 需大于 3.4 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>${spring.boot.version}</version>
</dependency>
<!-- agentscope.version 需大于 1.0.3 -->
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
  # 可更换为其他模型实现，比如openai
  dashscope:
    api-key: your-dashscope-api-key
  agent:
    name: my-assistant
  a2a:
    server:
      enabled: true
      card:
        name: My Assistant
        description: 一个基于 AgentScope 的智能助手
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

这样就完成了一个基本的 A2A Server 配置，Spring Boot 会自动创建 Web 端点并处理 A2A 请求。

如果你不想使用 Spring Boot，也可以手动创建 A2A Server：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;

// 1. 创建 ReActAgent
ReActAgent.Builder agentBuilder = ReActAgent.builder()
    .name("my-assistant")
    .sysPrompt("你是一个有用的助手");

// 2. 构建 A2A Server
DeploymentProperties deploymentProperties = new DeploymentProperties.Builder()
    .host("localhost") // 可替换为 你的主机地址，未传入时将自动获取本机第一个非回环地址作为 host
    .port(8080) // 必填，web 容器所暴露监听的端口号
    .build();

AgentScopeA2aServer a2aServer = AgentScopeA2aServer.builder(agentBuilder)
    .deploymentProperties(deploymentProperties)
    .build();

// 3. 在 Web 框架中导出端点（以 Spring Boot 为例）
// 获取 JSON-RPC 传输处理器， 在您的Web 框架中使用它处理请求
JsonRpcTransportWrapper jsonRpcTransport = 
    a2aServer.getTransportWrapper("JSON-RPC", JsonRpcTransportWrapper.class);

// 注意：你需要自己创建 Web 容器和 Controller 来处理请求
// 推荐使用 agentscope-a2a-spring-boot-starter，它会帮你创建这些组件

// 4. 当 Web 服务就绪后调用
a2aServer.postEndpointReady();
```

## 配置和使用

### 基本配置

要创建一个 A2A Server，您需要使用 `AgentScopeA2aServer.Builder` 来构建实例：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;

// 方式一：使用 ReActAgent.Builder
ReActAgent.Builder agentBuilder = ReActAgent.builder()
    .name("my-assistant")
    .sysPrompt("你是一个有用的助手");

AgentScopeA2aServer server1 = AgentScopeA2aServer.builder(agentBuilder)
    .deploymentProperties(DeploymentProperties.builder()
        .host("localhost")
        .port(8080)
        .build())
    .build();

// 方式二：使用自定义 AgentRunner
CustomAgentRunner customRunner = new CustomAgentRunner();
AgentScopeA2aServer server2 = AgentScopeA2aServer.builder(customRunner)
    .build();
```

### 构建器参数详解

A2A Server 的构建器支持以下配置参数：

| 参数                     | 类型                          | 描述                        |
|------------------------|-----------------------------|---------------------------|
| `agentCard`            | ConfigurableAgentCard       | 自定义 AgentCard 配置          |
| `withTransport`        | TransportProperties         | 添加支持的传输协议                 |
| `taskStore`            | TaskStore                   | 任务存储实现，默认使用内存存储           |
| `queueManager`         | QueueManager                | 队列管理器，默认使用内存队列            |
| `pushConfigStore`      | PushNotificationConfigStore | 推送通知配置存储，默认使用内存存储         |
| `pushSender`           | PushNotificationSender      | 推送通知发送器，默认使用基础推送发送器       |
| `executor`             | Executor                    | 执行器，默认使用 CachedThreadPool |
| `deploymentProperties` | DeploymentProperties        | 部署属性，用于生成默认的传输接口信息        |
| `withAgentRegistry`    | AgentRegistry               | 添加 Agent 注册表实现，用于服务注册     |

注意：传输协议端点（如 Web 容器、路由或控制器）需要开发者自行创建。推荐使用 `agentscope-a2a-spring-boot-starter`，它会自动创建 Web 容器及相关 Controller。

### ConfigurableAgentCard 配置

通过 ConfigurableAgentCard 可以配置生成的 AgentCard 元数据：

```java
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;

ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder()
    .name("My Assistant")
    .description("一个基于 AgentScope 的智能助手")
    .version("1.0.0")
    .provider(new AgentProvider("AgentScope", "https://agentscope.io"))
    .documentationUrl("https://docs.agentscope.io/a2a-server")
    .skills(Arrays.asList(
        new AgentSkill("text-generation", "文本生成"),
        new AgentSkill("question-answering", "问答")))
    .build();

AgentScopeA2aServer server = AgentScopeA2aServer.builder(agentBuilder)
    .agentCard(agentCard)
    .build();
```

### 与 Spring Boot 集成

当使用 Spring Boot 时，AgentScope 提供了自动配置功能来简化 A2A Server 的部署：

```yaml
# application.yml
agentscope:
  a2a:
    server:
      enabled: true
      card:
        name: My Spring Boot Assistant
        description: 通过 Spring Boot 自动配置的 A2A Server
        version: 1.0.0
```

相关的自动配置类 [AgentscopeA2aAutoConfiguration](../../../agentscope-extensions/agentscope-spring-boot-starters/agentscope-a2a-spring-boot-starter/src/main/java/io/agentscope/spring/boot/a2a/AgentscopeA2aAutoConfiguration.java) 会自动创建所需的 Bean 并暴露相应的端点。

## 高级使用示例

### 自定义 AgentRunner

```java
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Flux;

public class CustomAgentRunner implements AgentRunner {
    
    @Override
    public Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options) {
        // 自定义的 Agent 处理逻辑
        // 可以在这里添加预处理、后处理等逻辑
        
        // 调用实际的 Agent 实现
        return actualAgent.call(messages);
    }
    
    @Override
    public void stop(String taskId) {
        // 中断指定任务的执行
    }
    
    @Override
    public String getAgentName() {
        return "Custom Agent";
    }
    
    @Override
    public String getAgentDescription() {
        return "自定义 Agent 实现";
    }
}

// 使用自定义 AgentRunner
AgentScopeA2aServer server = AgentScopeA2aServer.builder(new CustomAgentRunner())
    .build();
```

## 拓展能力

### 添加自定义 AgentRegistry

AgentScope A2A Server允许添加自定义的 AgentRegistry 来实现Agent的自动注册到A2A的注册中心中。

```java
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;

public class CustomAgentRegistry implements AgentRegistry {
    
    @Override
    public void register(AgentCard agentCard, Set<TransportProperties> transports) {
        // 自定义注册逻辑，例如注册到外部服务发现系统
        System.out.println("Registering agent: " + agentCard.name());
    }
}

// 使用自定义注册表
AgentScopeA2aServer server = AgentScopeA2aServer.builder(agentBuilder)
    .withAgentRegistry(new CustomAgentRegistry())
    .build();

// 当服务就绪时触发注册
server.postEndpointReady();
```

### 配置多个传输协议

A2A Server 允许添加多个传输协议，用于支持不同的传输方式。添加其他传输协议时，需要实现以下拓展机制：

#### 1. 构建TransportWrapper及 TransportWrapperBuilder

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
        // 解析CustomRequest为A2A标准的Request， 比如SendMessageRequest。
        SendMessageRequest result = converRequest(body);
        Object result = requestHandler.handle(result);
        // 将A2A标准的相应体转化为自定义传输协议的相应体。
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
        // 会传入构建的参数，目前必要的参数主要有 AgentCard， A2aRequest的Handler，Executor线程池， 以及扩展的AgentCard
        return new CustomTransportWrapper(requestHandler);
    }
}
```

#### 2. 将CustomTransportWrapperBuilder作为SPI实现放到resource下

```shell
mdkir -p path_to_your_project/src/main/resources/META-INF/services/
cd path_to_your_project/src/main/resources/META-INF/services/
echo "CustomTransportWrapperBuilder.class.getCanonicalName" > io.agentscope.core.a2a.server.transport.TransportWrapperBuilder
# 例如 echo "io.agentscope.demo.CustomTransportWrapperBuilder" > io.agentscope.core.a2a.server.transport.TransportWrapperBuilder
```

#### 3. 配置传输协议

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

## 错误处理和监控

### 异常处理

A2A Server 内部集成了完善的异常处理机制，在各个处理环节都会捕获并适当地处理异常，确保系统的稳定性。

### 任务监控

通过 TaskStore 和 QueueManager 可以监控任务的执行状态：

```java
// 获取任务状态
TaskStore taskStore = server.getTaskStore(); // 需要通过反射或自定义方法获取
Task task = taskStore.getTask(taskId);
TaskStatus status = task.getStatus();
```

## 最佳实践

1. **推荐使用 Spring Boot Starter**：对于大多数应用场景，推荐使用 `agentscope-a2a-spring-boot-starter`，它可以自动处理 Web 容器和端点的创建。

2. **合理配置线程池**：根据实际负载情况配置合适的 executor，避免资源浪费或性能瓶颈。

3. **选择适当的存储实现**：对于生产环境，建议使用持久化的 TaskStore 和 QueueManager 实现，而不是默认的内存实现。

4. **安全配置**：通过 AgentCard 配置适当的安全方案，保护服务免受未授权访问。

5. **监控和日志**：启用详细的日志记录以便于问题排查和性能优化。

6. **合理的超时设置**：根据业务需求设置合适的任务超时时间，避免长时间阻塞。
