# AgentScope Quarkus Example

A sample application demonstrating how to use AgentScope with Quarkus framework.

## 🚀 Quick Start

### Prerequisites
- Java 17 or higher
- Maven 3.8+
- API key for your chosen model provider (DashScope, OpenAI, Gemini, or Anthropic)

### Running the Application

1. Set your API key:
```bash
export DASHSCOPE_API_KEY=your-api-key-here
```

2. Run in development mode (with hot reload):
```bash
mvn quarkus:dev
```

3. Test the agent:
```bash
# Health check
curl http://localhost:8080/agent/health

# Chat with the agent
curl -X POST http://localhost:8080/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello, who are you?"}'

# Ask a question
curl -X POST http://localhost:8080/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"What is the capital of France?"}'
```

## 📝 Configuration

Edit `src/main/resources/application.properties` to change:

- Model provider (dashscope, openai, gemini, anthropic)
- Model name
- Agent name and system prompt
- API keys

Example configurations:

### DashScope (Default)
```properties
agentscope.model.provider=dashscope
agentscope.dashscope.api-key=${DASHSCOPE_API_KEY}
agentscope.dashscope.model-name=qwen-plus
```

### OpenAI
```properties
agentscope.model.provider=openai
agentscope.openai.api-key=${OPENAI_API_KEY}
agentscope.openai.model-name=gpt-4
```

### Gemini
```properties
agentscope.model.provider=gemini
agentscope.gemini.api-key=${GEMINI_API_KEY}
agentscope.gemini.model-name=gemini-2.0-flash-exp
```

## 📦 Packaging

### JVM Package
```bash
mvn package
java -jar target/quarkus-app/quarkus-run.jar
```

### Native Image (requires GraalVM)
```bash
mvn package -Pnative
./target/quarkus-example-*-runner
```

## 🐳 Docker

### JVM-based Docker Image
```bash
mvn package
docker build -f src/main/docker/Dockerfile.jvm -t agentscope-quarkus .
docker run -p 8080:8080 -e DASHSCOPE_API_KEY=your-key agentscope-quarkus
```

### Native Docker Image
```bash
mvn package -Pnative -Dquarkus.native.container-build=true
docker build -f src/main/docker/Dockerfile.native -t agentscope-quarkus-native .
docker run -p 8080:8080 -e DASHSCOPE_API_KEY=your-key agentscope-quarkus-native
```

## 🔧 API Endpoints

### POST /agent/chat
Send a message to the agent and receive a response.

**Request:**
```json
{
  "message": "Your message here"
}
```

**Response:**
```json
{
  "response": "Agent's response here"
}
```

### GET /agent/health
Check if the agent is ready.

**Response:**
```json
{
  "status": "AgentScope agent is ready",
  "agentName": "QuarkusAssistant"
}
```

## 📚 Learn More

- [AgentScope Documentation](https://github.com/agentscope-ai/agentscope-java)
- [Quarkus Documentation](https://quarkus.io/)
- [Quarkus REST Guide](https://quarkus.io/guides/rest)

## 📄 License

Apache License 2.0
