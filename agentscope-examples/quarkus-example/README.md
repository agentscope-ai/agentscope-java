# AgentScope Quarkus Example

This is an example application demonstrating how to use AgentScope with Quarkus.

## 🚀 Running the Application

### Prerequisites

- Java 17 or later
- Maven 3.8+
- A valid API key from one of the supported providers (DashScope, OpenAI, Gemini, or Anthropic)

### Configuration

Set your API key as an environment variable:

```bash
export DASHSCOPE_API_KEY=your-api-key-here
```

Or configure it in `src/main/resources/application.properties`.

### Running in Dev Mode

```bash
mvn quarkus:dev
```

The application will start on http://localhost:8080

### Testing the Endpoints

```bash
# Health check
curl http://localhost:8080/agent/health

# Chat with the agent
curl -X POST http://localhost:8080/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello, who are you?"}'
```

## 🧪 Running Tests

**Note:** Tests are skipped by default because they require a valid API key and make real API calls.

To run tests locally with your API key:

```bash
# Set your API key
export DASHSCOPE_API_KEY=your-real-api-key

# Run tests
mvn test -DskipExampleTests=false
```

## 🐳 Docker

### Build JVM Image

```bash
mvn package
docker build -f src/main/docker/Dockerfile.jvm -t agentscope-quarkus .
docker run -i --rm -p 8080:8080 -e DASHSCOPE_API_KEY=your-key agentscope-quarkus
```

### Build Native Image

```bash
mvn package -Pnative
docker build -f src/main/docker/Dockerfile.native -t agentscope-quarkus-native .
docker run -i --rm -p 8080:8080 -e DASHSCOPE_API_KEY=your-key agentscope-quarkus-native
```

## 📚 More Information

- [AgentScope Documentation](https://github.com/agentscope-ai/agentscope-java)
- [Quarkus Documentation](https://quarkus.io/)
