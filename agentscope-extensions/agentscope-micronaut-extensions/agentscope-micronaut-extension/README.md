# AgentScope Micronaut Integration

Micronaut integration for AgentScope Java.

## Installation

Add the following dependency to your Micronaut project:

**Maven:**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-micronaut</artifactId>
    <version>1.0.7</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'io.agentscope:agentscope-micronaut:1.0.7'
```

## Configuration

Configure AgentScope in your `application.yml`:

> **⚠️ Security Note**: Never hardcode API keys in your configuration files. Always use environment variables or secure secret management systems. The examples below use `${ENV_VAR}` syntax to reference environment variables.

### Using DashScope (Default)

```yaml
agentscope:
  model:
    provider: dashscope
  
  dashscope:
    enabled: true
    api-key: ${DASHSCOPE_API_KEY}
    model-name: qwen-plus
    stream: true
    enable-thinking: true
  
  agent:
    enabled: true
    name: "Assistant"
    sys-prompt: "You are a helpful AI assistant."
    max-iters: 10
```

### Using OpenAI

```yaml
agentscope:
  model:
    provider: openai
  
  openai:
    enabled: true
    api-key: ${OPENAI_API_KEY}
    model-name: gpt-4.1-mini
    stream: true
  
  agent:
    enabled: true
    name: "Assistant"
    sys-prompt: "You are a helpful AI assistant."
    max-iters: 10
```

### Using Gemini

```yaml
agentscope:
  model:
    provider: gemini
  
  gemini:
    enabled: true
    api-key: ${GEMINI_API_KEY}
    model-name: gemini-2.0-flash
    stream: true
  
  agent:
    enabled: true
    name: "Assistant"
    sys-prompt: "You are a helpful AI assistant."
    max-iters: 10
```

### Using Anthropic

```yaml
agentscope:
  model:
    provider: anthropic
  
  anthropic:
    enabled: true
    api-key: ${ANTHROPIC_API_KEY}
    model-name: claude-sonnet-4.5
    stream: true
  
  agent:
    enabled: true
    name: "Assistant"
    sys-prompt: "You are a helpful AI assistant."
    max-iters: 10
```

## Usage

Inject the beans into your Micronaut components:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import jakarta.inject.Inject;

@Controller("/agent")
public class AgentController {

    @Inject
    private Model model;
    
    // Note: Memory, Toolkit, and ReActAgent are prototype-scoped
    // Inject them per-request or use Provider
    
    @Get("/chat")
    public String chat(String message, ReActAgent agent) {
        return agent.call(message).block().getContent();
    }
}
```

## Bean Scopes

- **Model**: `@Singleton` - Thread-safe, shared across the application
- **Memory**: `@Prototype` - Not thread-safe, create per session
- **Toolkit**: `@Prototype` - Not thread-safe, create per session
- **ReActAgent**: `@Prototype` - Not thread-safe, create per session

## Features

- ✅ Auto-configuration for multiple LLM providers
- ✅ Type-safe configuration properties
- ✅ Compile-time dependency injection
- ✅ GraalVM native image support
- ✅ Minimal overhead and fast startup

## Documentation

For more information, visit the [AgentScope Java documentation](https://java.agentscope.io/).
