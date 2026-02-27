# PlanNotebook Web Application

A Spring Boot web application demonstrating the PlanNotebook component of AgentScope Java SDK with real-time streaming capabilities.

## Overview

PlanNotebook is an interactive example that showcases how to integrate the AgentScope Java SDK's planning capabilities into a web application. It features:

- **SSE Streaming**: Real-time agent response streaming via Server-Sent Events
- **Plan Management**: Create, update, and track plans with subtasks
- **Interactive Web UI**: Built-in web interface for chat and plan visualization
- **REST API**: Comprehensive API for programmatic plan management

## Project Structure

```
plan-notebook/
├── pom.xml                          # Maven configuration
├── src/main/java/.../plannotebook/
│   ├── PlanNotebookWebApplication.java   # Spring Boot entry point
│   ├── controller/
│   │   ├── ChatController.java           # Chat API endpoints (SSE streaming)
│   │   └── PlanController.java           # Plan management API endpoints
│   ├── service/
│   │   ├── AgentService.java             # ReActAgent configuration and chat logic
│   │   ├── PlanService.java              # Plan operations and SSE broadcasting
│   │   └── FileToolMock.java             # Mock file tools for demonstration
│   └── dto/
│       ├── ChatRequest.java              # Chat request DTO
│       ├── PlanResponse.java             # Plan response DTO
│       ├── SubTaskRequest.java           # Subtask creation/update DTO
│       └── SubTaskResponse.java          # Subtask response DTO
└── src/main/resources/
    ├── application.yml                   # Spring Boot configuration
    ├── logback.xml                       # Logging configuration
    └── static/
        └── index.html                    # Web UI
```

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- DashScope API Key (from [Alibaba Cloud](https://dashscope.aliyun.com/))

## Quick Start

### 1. Set Environment Variable

```bash
export DASHSCOPE_API_KEY=your_api_key_here
```

### 2. Build and Run

**Using JAR**
```bash
# Build the project

cd /{workplace}/agentscope-java
mvn clean package

# Run the JAR
cd /{workplace}/agentscope-java/agentscope-examples/multi-component/plan-notebook
java -jar target/agentscope-examples-multi-component-plan-notebook-*.jar
```

### 3. Access the Application

Open your browser and navigate to: **http://localhost:8080**

## API Reference

### Chat API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/chat?message=...&sessionId=...` | Chat with agent (SSE streaming) |
| GET | `/api/health` | Health check |
| POST | `/api/reset` | Reset agent and clear all data |

### Human-in-the-Loop (HITL) API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/resume` | Resume agent execution after user review (SSE streaming) |
| GET | `/api/paused` | Check if the agent is currently paused |
| POST | `/api/stop` | Request the agent to pause after the next plan tool execution |
| GET | `/api/stop-requested` | Check if a stop has been requested |

### Plan API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/plan/stream` | Subscribe to plan state changes (SSE) |
| GET | `/api/plan` | Get current plan state |
| POST | `/api/plan` | Create a new plan |
| PUT | `/api/plan` | Update plan name/description/expectedOutcome |
| POST | `/api/plan/subtasks` | Add a subtask at specified index |
| PUT | `/api/plan/subtasks/{idx}` | Revise a subtask |
| DELETE | `/api/plan/subtasks/{idx}` | Delete a subtask |
| PATCH | `/api/plan/subtasks/{idx}/state` | Update subtask state |
| POST | `/api/plan/subtasks/{idx}/finish` | Finish a subtask with outcome |
| POST | `/api/plan/finish` | Finish the current plan |

## Configuration

### application.yml

```yaml
server:
  port: 8080

logging:
  level:
    io.agentscope: DEBUG
```

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DASHSCOPE_API_KEY` | Yes | API key for DashScope LLM service |

## License

Apache License 2.0 - see [LICENSE](./LICENSE) for details.
