# AgentScope Quarkus Integration

This directory contains the Quarkus integration for AgentScope, providing both a powerful extension and an easy-to-use starter.

## 📦 Structure

```
agentscope-extensions/agentscope-extensions-quarkus/
├── runtime/              # Quarkus Extension Runtime
│   └── Configuration and core integration
└── deployment/           # Quarkus Extension Deployment
    └── Build-time processing and native image support
```

## 🚀 Quick Start

### Using the Starter (Recommended for most users)

Add the starter dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-quarkus-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Configure in `application.properties`:

```properties
# Model Provider
agentscope.model.provider=dashscope

# DashScope Configuration
agentscope.dashscope.api-key=${DASHSCOPE_API_KEY}
agentscope.dashscope.model-name=qwen-plus
agentscope.dashscope.stream=false

# Agent Configuration
agentscope.agent.name=MyAssistant
agentscope.agent.sys-prompt=You are a helpful AI assistant.
```

Inject and use:

```java
@Path("/agent")
public class AgentResource {
    
    @Inject
    ReActAgent agent;  // Auto-configured!
    
    @POST
    @Path("/chat")
    public String chat(String message) {
        Msg response = agent.call(
            Msg.builder()
               .role(MsgRole.USER)
               .content(TextBlock.builder().text(message).build())
               .build()
        ).block();
        
        return response.getTextContent();
    }
}
```

### Using the Extension (For advanced users)

Add the extension dependency:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-quarkus-extension</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Create your own CDI producers with full control:

```java
@ApplicationScoped
public class MyAgentProducer {
    
    @Produces
    @ApplicationScoped
    public Model createModel() {
        return new DashScopeChatModel.Builder()
            ModelConfig.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .build()
        );
    }
    
    @Produces
    @Dependent
    public ReActAgent createAgent(Model model) {
        return ReActAgent.builder()
            .name("CustomAgent")
            .model(model)
            .memory(new InMemoryMemory())
            .build();
    }
}
```

## 🔧 Features

### Extension Features
- ✅ **GraalVM Native Image Support** - Full reflection registration for native compilation
- ✅ **CDI Integration** - First-class dependency injection support
- ✅ **Build-time Optimization** - Quarkus build step processing
- ✅ **Configuration Mapping** - Type-safe configuration with `@ConfigMapping`

### Starter Features
- ✅ **Auto-Configuration** - Zero-config agent setup
- ✅ **Multiple Providers** - Support for DashScope, OpenAI, Gemini, Anthropic
- ✅ **Flexible Scoping** - Proper CDI scopes for different components
- ✅ **Properties-based Config** - Simple `application.properties` configuration

## 📚 Configuration Reference

### Model Providers

#### DashScope (Alibaba Cloud)
```properties
agentscope.model.provider=dashscope
agentscope.dashscope.api-key=${DASHSCOPE_API_KEY}
agentscope.dashscope.model-name=qwen-plus
agentscope.dashscope.stream=false
agentscope.dashscope.enable-thinking=false
```

#### OpenAI
```properties
agentscope.model.provider=openai
agentscope.openai.api-key=${OPENAI_API_KEY}
agentscope.openai.model-name=gpt-4
agentscope.openai.stream=false
```

#### Gemini (Google AI)
```properties
agentscope.model.provider=gemini
agentscope.gemini.api-key=${GEMINI_API_KEY}
agentscope.gemini.model-name=gemini-2.0-flash-exp
agentscope.gemini.stream=false
```

#### Gemini (Vertex AI)
```properties
agentscope.model.provider=gemini
agentscope.gemini.use-vertex-ai=true
agentscope.gemini.project=your-gcp-project
agentscope.gemini.location=us-central1
agentscope.gemini.model-name=gemini-2.0-flash-exp
```

#### Anthropic (Claude)
```properties
agentscope.model.provider=anthropic
agentscope.anthropic.api-key=${ANTHROPIC_API_KEY}
agentscope.anthropic.model-name=claude-3-5-sonnet-20241022
agentscope.anthropic.stream=false
```

### Agent Configuration
```properties
agentscope.agent.name=MyAssistant
agentscope.agent.sys-prompt=You are a helpful AI assistant.
agentscope.agent.max-iters=10
```

## 🏃 Running the Example

```bash
# Development mode (with hot reload)
cd agentscope-examples/quarkus-example
export DASHSCOPE_API_KEY=your-api-key
mvn quarkus:dev

# Package and run
mvn package
java -jar target/quarkus-app/quarkus-run.jar

# Build native image (requires GraalVM)
mvn package -Pnative
./target/quarkus-example-*-runner
```

Test the endpoint:
```bash
curl -X POST http://localhost:8080/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello, who are you?"}'
```

## 🐳 Docker

Build Docker image:
```bash
mvn package
docker build -f src/main/docker/Dockerfile.jvm -t agentscope-quarkus .
docker run -p 8080:8080 -e DASHSCOPE_API_KEY=your-key agentscope-quarkus
```

Build native Docker image:
```bash
mvn package -Pnative -Dquarkus.native.container-build=true
docker build -f src/main/docker/Dockerfile.native -t agentscope-quarkus-native .
docker run -p 8080:8080 -e DASHSCOPE_API_KEY=your-key agentscope-quarkus-native
```

## 📖 Learn More

- [Quarkus Documentation](https://quarkus.io/guides/)
- [AgentScope Documentation](https://github.com/agentscope-ai/agentscope-java)
- [Quarkus Extension Guide](https://quarkus.io/guides/building-my-first-extension)

## 🤝 Contributing

Contributions are welcome! Please see the main project [CONTRIBUTING.md](../../CONTRIBUTING.md) for guidelines.

## 📄 License

Apache License 2.0 - See [LICENSE](../../LICENSE) for details.
