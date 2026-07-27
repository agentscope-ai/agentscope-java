---
kind: error_handling
name: AgentScope Java 错误处理体系：分层异常与可重试 HTTP 语义
slug: error_handling
category: error_handling
scope:
    - '**'
---

## 1. 采用的错误处理体系

AgentScope Java 采用“领域分层 + 运行时异常”的统一错误模型，核心约定如下：

- **全部为 RuntimeException**：框架内自定义异常均继承 `RuntimeException`，避免强制 try/catch 污染调用链；上层业务可按需捕获。
- **按领域划分异常根类型**：每个子系统定义自己的顶层异常类，形成清晰的异常树，便于在中间件/网关层做统一拦截与分类。
- **HTTP 可重试语义通过接口抽象**：`ModelHttpException` 作为所有模型提供者的 HTTP 异常契约，配合 `HttpTransportException.isRetryable()` 实现 provider-neutral 的重试决策。
- **工具暂停与优雅关闭等控制流异常**：使用专门的异常（如 `ToolSuspendException`、`AgentShuttingDownException`）表达“非错误但需要中断/挂起”的控制信号，由 ReActAgent 和中间件识别并转换为用户可见的 ToolResultBlock 或拒绝请求。
- **组合异常聚合多 Agent 错误**：`CompositeAgentException` 用于在多子 Agent 并发执行时汇总多个失败原因，对外暴露结构化 `AgentExceptionInfo` 列表。

## 2. 关键文件与包

- 核心异常定义
  - `agentscope-core/src/main/java/io/agentscope/core/model/ModelException.java` — 模型调用通用异常，携带 modelName/provider 上下文
  - `agentscope-core/src/main/java/io/agentscope/core/model/ModelHttpException.java` — HTTP 状态码 + 可重试判断的契约接口
  - `agentscope-core/src/main/java/io/agentscope/core/model/transport/HttpTransportException.java` — HTTP 传输异常，封装 statusCode/responseBody/isRetryable()
  - `agentscope-core/src/main/java/io/agentscope/core/model/transport/websocket/WebSocketTransportException.java` — WebSocket 传输异常，附带 URL/connectionState/headers
  - `agentscope-core/src/main/java/io/agentscope/core/formatter/FormatterException.java` — 消息格式化/解析异常
  - `agentscope-core/src/main/java/io/agentscope/core/util/JsonException.java` — JSON 编解码异常
  - `agentscope-core/src/main/java/io/agentscope/core/tool/ToolSuspendException.java` — 工具执行挂起控制信号
  - `agentscope-core/src/main/java/io/agentscope/core/shutdown/AgentShuttingDownException.java` — 优雅关闭期间拒绝请求的信号
  - `agentscope-core/src/main/java/io/agentscope/core/exception/CompositeAgentException.java` — 多 Agent 错误聚合

- 扩展模块中的领域异常（示例）
  - OpenAI: `agentscope-extensions-model-openai/.../exception/OpenAIException` 及子类（BadRequest/RateLimit/PermissionDenied 等），实现 `ModelHttpException`
  - DashScope: 内部静态类 `DashScopeEncryptionException` / `DashScopeHttpException`
  - Ollama: 内部静态类 `OllamaHttpClient.OllamaHttpException`
  - AG-UI: `AguiException` 及其子类
  - RAG 集成: `DifyApiException`、`HayStackApiException`、`RAGFlowApiException`、`EmbeddingException`、`ReaderException`、`VectorStoreException`

## 3. 架构与设计约定

### 3.1 异常层次结构

```
RuntimeException
├── ModelException (模型层)
│   └── (各 Provider 具体异常，实现 ModelHttpException)
├── HttpTransportException (传输层)
├── WebSocketTransportException (WebSocket 传输层)
├── FormatterException (格式层)
├── JsonException (序列化层)
├── ToolSuspendException (工具控制流)
├── AgentShuttingDownException (生命周期控制流)
└── CompositeAgentException (聚合型)
```

### 3.2 可重试策略

- `HttpTransportException.isRetryable()`：无 statusCode 的连接错误默认可重试；429 与 5xx 视为可重试。
- `ModelHttpException.isRetryableHttpStatus()`：仅基于 HTTP 状态码判断，保持 provider 无关。
- `ExecutionConfig` 中对 `HttpTransportException` 做特殊分支，优先使用内置 isRetryable()。

### 3.3 控制流异常 vs 错误异常

- `ToolSuspendException` 不是真正的错误，而是“需要外部执行”的信号，ReActAgent 将其转换为 `ToolResultBlock.suspended(...)` 并返回 `GenerateReason.TOOL_SUSPENDED`。
- `AgentShuttingDownException` 在优雅关闭阶段被抛出，ReActAgent 通过 `Mono.error(new AgentShuttingDownException())` 快速失败，避免新请求进入。

### 3.4 错误传播与包装

- 底层 I/O/网络异常通常被包装为对应领域的异常（如 `IOException` → `HttpTransportException` / `FormatterException`），保留 cause 链以便调试。
- 多 Agent 场景下，ReActAgent 收集各子 Agent 的异常，最终抛出一个 `CompositeAgentException`，其 `getMessage()` 会拼接每个 `AgentExceptionInfo` 的 agentId/name 与原始异常信息。

## 4. 开发者应遵循的规则

1. **不要抛出受检异常**：框架内新增异常一律继承 `RuntimeException`，保持 API 简洁。
2. **按领域选择顶层异常**：
   - 模型调用失败 → `ModelException` 或其子类（若涉及 HTTP，尽量实现 `ModelHttpException`）。
   - 网络/HTTP 层问题 → `HttpTransportException` / `WebSocketTransportException`。
   - 消息格式/JSON 问题 → `FormatterException` / `JsonException`。
3. **利用可重试语义**：对 HTTP 异常实现 `ModelHttpException` 并提供准确的 `getStatusCode()`，让上层自动获得 429/5xx 的可重试判定。
4. **用控制流异常表达意图**：
   - 工具需要外部执行 → 抛 `ToolSuspendException`，不要返回空结果或特殊值。
   - 优雅关闭期间拒绝请求 → 抛 `AgentShuttingDownException`。
5. **保留 cause 链**：包装异常时必须传入原始 cause，便于日志与链路追踪定位根因。
6. **多 Agent 聚合错误**：当一次调用涉及多个子 Agent 且部分失败时，使用 `CompositeAgentException` 聚合，而非只抛第一个错误。
7. **避免吞掉异常**：仅在基础设施层（如 trace exporter、回调钩子）中 catch `Throwable` 并记录日志后忽略，业务逻辑层不应 catch 并静默丢弃。
