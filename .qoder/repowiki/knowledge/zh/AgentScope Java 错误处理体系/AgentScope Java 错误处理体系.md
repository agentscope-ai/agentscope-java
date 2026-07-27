---
kind: error_handling
name: AgentScope Java 错误处理体系
category: error_handling
scope:
    - '**'
source_files:
    - agentscope-core/src/main/java/io/agentscope/core/model/ModelException.java
    - agentscope-core/src/main/java/io/agentscope/core/model/ModelHttpException.java
    - agentscope-core/src/main/java/io/agentscope/core/model/transport/HttpTransportException.java
    - agentscope-core/src/main/java/io/agentscope/core/model/transport/websocket/WebSocketTransportException.java
    - agentscope-core/src/main/java/io/agentscope/core/exception/CompositeAgentException.java
    - agentscope-core/src/main/java/io/agentscope/core/tool/ToolSuspendException.java
    - agentscope-core/src/main/java/io/agentscope/core/shutdown/AgentShuttingDownException.java
    - agentscope-core/src/main/java/io/agentscope/core/formatter/FormatterException.java
    - agentscope-core/src/main/java/io/agentscope/core/util/JsonException.java
    - agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/src/main/java/io/agentscope/extensions/model/openai/exception/OpenAIException.java
---

## 1. 整体架构与策略

AgentScope Java 采用**分层 RuntimeException + 领域异常接口**的错误处理体系，核心原则：

- **全部为运行时异常**：框架内所有自定义异常均继承 `RuntimeException`，不强制调用方捕获，便于在 Agent 编排链路中自由传播。
- **按领域划分异常层次**：模型、传输层、格式化、工具、关闭流程各自拥有独立异常类型，避免单一异常膨胀。
- **HTTP 状态码抽象**：通过 `ModelHttpException` 接口统一暴露 HTTP 语义（状态码、可重试性），使上层重试逻辑与具体 Provider 解耦。
- **组合异常聚合多子代理错误**：`CompositeAgentException` 用于在并行/级联 Agent 执行时汇总多个子代理的失败信息。

## 2. 核心异常类型与职责

### 2.1 模型层异常

| 类型 | 位置 | 职责 |
|------|------|------|
| `ModelException` | `core/model/ModelException.java` | 所有模型操作失败的根异常，携带 `modelName`、`provider` 上下文，`toString()` 自动拼接模型信息 |
| `ModelHttpException` | `core/model/ModelHttpException.java` | 可暴露 HTTP 状态码的异常契约；默认 `isRetryableHttpStatus()` 将 429 和 5xx 标记为可重试 |
| `HttpTransportException` | `core/model/transport/HttpTransportException.java` | HTTP 传输层异常，封装 `statusCode`、`responseBody`，提供 `isClientError()` / `isServerError()` / `isRetryable()` 等便捷方法 |
| `WebSocketTransportException` | `core/model/transport/websocket/WebSocketTransportException.java` | WebSocket 传输层异常，附带 URL、连接状态、请求头以便诊断 |

### 2.2 领域专用异常

| 类型 | 位置 | 用途 |
|------|------|------|
| `FormatterException` | `core/formatter/FormatterException.java` | 消息格式化/响应解析失败 |
| `JsonException` | `core/util/JsonException.java` | JSON 编解码错误（包装 Jackson 等底层异常） |
| `ToolSuspendException` | `core/tool/ToolSuspendException.java` | 工具需要外部执行时抛出，框架将其转为挂起的 `ToolResultBlock` 并返回 `GenerateReason.TOOL_SUSPENDED` |
| `AgentShuttingDownException` | `core/shutdown/AgentShuttingDownException.java` | 优雅关闭期间拒绝新请求，默认消息 "Operation interrupted due to system shutting down, please retry" |
| `CompositeAgentException` | `core/exception/CompositeAgentException.java` | 聚合多个子代理异常的复合异常，内部使用 `AgentExceptionInfo` record 记录每个子代理的 agentId/name 及原始 throwable |

### 2.3 Provider 特定异常（以 OpenAI 为例）

OpenAI 扩展模块定义了一套细粒度异常类，全部实现 `ModelHttpException`：

- `OpenAIException` — 基类，提供 `create(statusCode, message, errorCode, responseBody)` 工厂方法根据状态码自动选择子类
- `BadRequestException`(400)、`AuthenticationException`(401)、`PermissionDeniedException`(403)、`NotFoundException`(404)、`UnprocessableEntityException`(422)、`RateLimitException`(429)、`InternalServerException`(5xx)

其他 Provider（DashScope、Ollama、Gemini 等）也遵循相同模式：在各自模块内定义 `*Exception` 并实现 `ModelHttpException`，由 `ChatModelBase` 或客户端统一转换为 `ModelException` 向上抛出。

## 3. 错误传播与中间件集成

- **ReActAgent 主循环**：在工具调用、模型调用、子代理调用等关键路径上捕获异常，必要时包装为 `ModelException` 或 `CompositeAgentException` 继续传播。
- **GracefulShutdownMiddleware**：优雅关闭中间件在检测到系统关闭时将入站请求包装为 `AgentShuttingDownException` 快速失败。
- **Hook 机制**：`hook/ErrorEvent` 允许 Hook 订阅错误事件，实现统一的错误观测与上报。
- **重试决策**：上层（如 `ChatModelBase` 或业务代码）通过检查异常是否实现 `ModelHttpException` 并调用 `isRetryableHttpStatus()` 来决定是否重试，无需感知具体 Provider 异常类型。

## 4. 开发者约定

1. **不要抛出 Checked Exception**：框架内所有自定义异常均为 `RuntimeException`，调用方无需显式 try-catch。
2. **优先抛领域异常**：模型相关错误抛 `ModelException`，HTTP 层错误抛 `HttpTransportException`，JSON 错误抛 `JsonException`，避免直接抛 `IOException` / `JsonProcessingException`。
3. **Provider 扩展应实现 `ModelHttpException`**：自定义模型 Provider 的异常需实现 `getStatusCode()`，以便上层基于状态码做通用重试。
4. **使用 `ModelException` 构造函数携带 model/provider**：当错误来自特定模型实例时，使用带 `modelName`、`provider` 参数的构造器，便于日志与调试。
5. **工具外部执行用 `ToolSuspendException`**：需要用户确认或外部执行的工具应抛出此异常而非返回错误结果。
6. **优雅关闭场景抛 `AgentShuttingDownException`**：在 shutdown 钩子或中间件中检测关闭状态后抛出该异常，让调用方可区分「正常重试」和「系统关闭」。

## 5. 关键文件清单

- `agentscope-core/src/main/java/io/agentscope/core/model/ModelException.java`
- `agentscope-core/src/main/java/io/agentscope/core/model/ModelHttpException.java`
- `agentscope-core/src/main/java/io/agentscope/core/model/transport/HttpTransportException.java`
- `agentscope-core/src/main/java/io/agentscope/core/model/transport/websocket/WebSocketTransportException.java`
- `agentscope-core/src/main/java/io/agentscope/core/exception/CompositeAgentException.java`
- `agentscope-core/src/main/java/io/agentscope/core/tool/ToolSuspendException.java`
- `agentscope-core/src/main/java/io/agentscope/core/shutdown/AgentShuttingDownException.java`
- `agentscope-core/src/main/java/io/agentscope/core/formatter/FormatterException.java`
- `agentscope-core/src/main/java/io/agentscope/core/util/JsonException.java`
- `agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/src/main/java/io/agentscope/extensions/model/openai/exception/OpenAIException.java`
