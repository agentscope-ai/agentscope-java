# AgentScope Quarkus Integration

This directory contains the Quarkus extension for AgentScope, providing seamless integration with the Quarkus framework.

## 📦 Structure

```
agentscope-extensions/agentscope-extensions-quarkus/
├── agentscope-quarkus-extension/              # Quarkus Extension Runtime
│   ├── Configuration and core integration
│   └── Auto-configuration with CDI producers
└── agentscope-quarkus-extension-deployment/   # Quarkus Extension Deployment
    └── Build-time processing and native image support
```

## 🚀 Quick Start

Add the extension dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-quarkus-extension</artifactId>
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

Inject and use in your Quarkus application:

```java
@Path("/agent")
public class AgentResource {
    
    @Inject
    ReActAgent agent;  // Auto-configured from application.properties!
    
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

### Advanced Usage: Custom Producers

If you need full control, you can override the default beans by providing your own producers:

```java
@ApplicationScoped
public class MyCustomProducer {
    
    @Produces
    @ApplicationScoped
    public Model createCustomModel() {
        // Override default Model bean
        return DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-max")
                .temperature(0.8)
                .build();
    }
    
    @Produces
    @Dependent
    public ReActAgent createCustomAgent(Model model, Memory memory, Toolkit toolkit) {
        // Override default ReActAgent bean with custom configuration
        return ReActAgent.builder()
            .name("CustomAgent")
            .model(model)
            .memory(memory)
            .toolkit(toolkit)
            .maxIters(20)
            .build();
    }
}
```

## 🔧 Features

- ✅ **Auto-Configuration** - Zero-config agent setup with sensible defaults
- ✅ **Multiple Providers** - Support for DashScope, OpenAI, Gemini, Anthropic
- ✅ **GraalVM Native Image Support** - Full reflection registration for native compilation
- ✅ **CDI Integration** - First-class dependency injection support
- ✅ **Build-time Optimization** - Quarkus build step processing
- ✅ **Configuration Mapping** - Type-safe configuration with `@ConfigMapping`
- ✅ **Flexible Scoping** - Proper CDI scopes for different components (@ApplicationScoped, @Dependent)
- ✅ **Overridable Beans** - Easy to override default beans with custom implementations

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
