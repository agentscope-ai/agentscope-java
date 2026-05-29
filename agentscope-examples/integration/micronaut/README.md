# AgentScope Micronaut Example

Simple example demonstrating AgentScope integration with Micronaut framework dependency injection.

## Features

✅ **Micronaut Dependency Injection** - Beans configured via application.yml  
✅ **Multiple LLM Providers** - DashScope, OpenAI, Gemini, Anthropic  
✅ **Simple Configuration** - Just set environment variables and run

## Quick Start

### 1. Prerequisites

- Java 17 or later
- Maven 3.8+

### 2. Configuration

The example uses configuration from `src/main/resources/application.yml`. Set your API key via environment variable:

```bash
export DASHSCOPE_API_KEY=your-api-key
```

### 3. Run

```bash
mvn clean compile exec:java
```

## How It Works

This example demonstrates:

1. **Micronaut ApplicationContext** - Starts the DI container
2. **Bean Injection** - `ReActAgent` is injected from Micronaut factory
3. **Configuration** - All settings loaded from `application.yml`

The key difference from manual setup is that beans are created and configured automatically by Micronaut.

## Configuration

You can change the LLM provider in `application.yml`:

```yaml
agentscope:
  model:
    provider: dashscope  # or: openai, gemini, anthropic
  
  dashscope:
    api-key: ${DASHSCOPE_API_KEY}
    model-name: qwen-plus
```

See the [Micronaut Integration README](../../agentscope-extensions/agentscope-micronaut/README.md) for full configuration options.
