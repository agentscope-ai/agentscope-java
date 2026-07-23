# AG-UI

`agentscope-extensions-agui` 把 AgentScope v2 的 `AgentEvent` 流转换为 [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) 事件，让前端 UI 可以实时渲染 agent 的运行过程，包括文本、推理内容、工具调用、状态、自定义事件、token usage 和 HITL interrupt。

## 何时使用

- 需要把 AgentScope agent 接入 AG-UI 兼容前端或自研 Chat UI。
- 需要以 SSE 流式输出 `RUN_*`、`TEXT_MESSAGE_*`、`TOOL_CALL_*`、`CUSTOM` 等 AG-UI 事件。
- 需要前端工具、用户审批中断、运行上下文或自定义事件转换扩展。

## 添加依赖

手动使用协议适配器时添加：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-agui</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Spring Boot 应用直接使用 starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-agui-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import reactor.core.publisher.Flux;

AguiAdapterConfig config = AguiAdapterConfig.builder()
    .enableReasoning(true)
    .emitTokenUsage(true)
    .runTimeout(Duration.ofMinutes(5))
    .build();

AguiAgentAdapter adapter = new AguiAgentAdapter(agent, config);

// 前端通过 SSE 拿到的事件
Flux<AguiEvent> events = adapter.run(runAgentInput);
```

`RunAgentInput` 由前端传入，包含 `threadId`、`runId`、`messages`、`tools`、`state`等。适配器内部完成消息转换、调用 Agent 流式 API，再把事件映射到 AG-UI。

## 事件映射

v2 正常链路以 `AgentEvent` 为输入，内置 converter 负责语义映射，未映射事件会回退为官方 `RAW` 事件。

| AgentScope 事件 / 内容               | AG-UI 事件 |
|----------------------------------| --- |
| `AgentStartEvent`                | `RUN_STARTED` |
| `AgentEndEvent`                  | `RUN_FINISHED` |
| 文本                               | `TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT` / `TEXT_MESSAGE_END` |
| 推理（`enableReasoning=true`）       | `REASONING_MESSAGE_START` / `REASONING_MESSAGE_CONTENT` / `REASONING_MESSAGE_END` |
| 工具调用与参数增量                        | `TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END` |
| 工具结果                             | `TOOL_CALL_RESULT` |
| `CustomEvent`                    | `CUSTOM` |
| token usage（`emitTokenUsage=true`） | `CUSTOM`，`name=token_usage` |
| 未映射 `AgentEvent`                 | `RAW`，包含官方 `event` 和 `source` 字段 |

正常运行的 `RUN_STARTED` 和 `RUN_FINISHED` 由上游 `AgentStartEvent` / `AgentEndEvent` 决定。正常流结束但上游没有发 `AgentEndEvent` 时，adapter 不会额外补 `RUN_FINISHED`。异常路径会输出带 `timestamp` 的 `RUN_ERROR`，并补发一个 `RUN_FINISHED`。

## AG-UI Base Event Properties

所有 `AguiEvent` 都支持官方 base event properties：可选 `timestamp` 和 `rawEvent`。

默认配置不会启用 `BaseEventPropertiesEnricher`，因此框架不会默认给所有事件补 `timestamp`，也不会默认暴露内部 `AgentEvent` 作为 `rawEvent`。如需给事件补时间戳，可以显式开启默认 enricher：

```java
AguiAdapterConfig config = AguiAdapterConfig.builder()
    .baseEventPropertiesEnricherEnabled(true)
    .build();
```

`BaseEventPropertiesEnricher` 只会填充缺失的 `timestamp`，不写入 `rawEvent`。如果要暴露 `rawEvent`，请注册自定义 `AguiEventEnricher`。

Spring Boot starter 不会隐式启用默认 base properties enricher。需要该行为时，可以声明一个 `BaseEventPropertiesEnricher` bean，或声明自己的 `AguiEventEnricher` bean。

## 自定义 Converter 与 Enricher

`AgentEventConverter` 用于扩展或覆盖语义映射。同一个 `AgentEvent` 类型上，用户 converter 会覆盖内置 converter。

```java
@Bean
AgentEventConverter customEventConverter() {
    return new AgentEventConverter() {
        @Override
        public Set<Class<? extends AgentEvent>> eventTypes() {
            return Set.of(CustomEvent.class);
        }

        @Override
        public void convert(AgentEvent event, AguiStreamContext context) {
            CustomEvent customEvent = (CustomEvent) event;
            context.emit(new AguiEvent.Custom(
                context.getThreadId(),
                context.getRunId(),
                customEvent.getName(),
                customEvent.getValue()));
        }
    };
}
```

`AguiEventEnricher` 在 converter 之后执行，适合处理 `timestamp`、`rawEvent`、追踪字段等横切属性，也可以修改、追加或过滤 converter 输出的事件。

```java
@Bean
AguiEventEnricher timestampEnricher() {
    return (source, events, context) -> events.stream()
        .map(event -> AguiEvents.withBaseProperties(
            event,
            event.timestamp() != null ? event.timestamp() : System.currentTimeMillis(),
            event.rawEvent()))
        .toList();
}
```

Spring Boot starter 会自动收集 `AgentEventConverter` 和 `AguiEventEnricher` bean，并使用 `orderedStream()` 保留 `@Order` / `Ordered` 顺序。

## Token Usage

Token usage 默认不发送。手动配置：

```java
AguiAdapterConfig config = AguiAdapterConfig.builder()
    .emitTokenUsage(true)
    .build();
```

Spring Boot 配置：

```yaml
agentscope:
  agui:
    emit-token-usage: true
```

开启后，每个携带 usage 的 `ModelCallEndEvent` 会输出一个 `CUSTOM` 事件：`delta` 表示当前模型调用消耗，`cumulative` 表示本次 AG-UI run 内累计消耗。

## RuntimeContext

`AguiAgentAdapter.run(input, runtimeContext)` 支持调用方传入自定义 `RuntimeContext`。适配器会先复制调用方 context，再覆盖 AG-UI 协议元数据，确保默认元数据不会因为自定义 context 丢失。

| RuntimeContext 内容 | 来源 |
| --- | --- |
| `sessionId` | `RunAgentInput.threadId` |
| `RunAgentInput.class` | 完整 `RunAgentInput` |
| `agui.threadId` | `RunAgentInput.threadId` |
| `agui.runId` | `RunAgentInput.runId` |
| `agui.messages` | `RunAgentInput.messages` |
| `agui.tools` | `RunAgentInput.tools` |
| `agui.context` | `RunAgentInput.context` |
| `agui.state` | `RunAgentInput.state` |
| `agui.forwardedProps` | `RunAgentInput.forwardedProps` |

由于 `sessionId` 始终来自 `threadId`，同一个 agent 实例在不同 AG-UI thread 之间保持会话隔离。

## Spring Boot 集成

starter 会自动注册 MVC 或 WebFlux 入口。常用配置如下：

```yaml
agentscope:
  agui:
    path-prefix: /agui
    cors-enabled: true
    run-timeout: 10m
    default-agent-id: default
    enable-path-routing: true
    agent-id-header: X-Agent-Id
    emit-state-events: true
    emit-tool-call-args: true
    emit-token-usage: false
    enable-reasoning: false
    server-side-memory: false
```

可以通过 bean 扩展默认链路：

- `AgentEventConverter`：注册自定义事件语义映射。
- `AguiEventEnricher`：注册事件横切增强。
- `AguiRuntimeContextResolver`：为每次 Web 请求注入自定义 `RuntimeContext`。
- `AguiAgentAdapterFactory`：替换默认 `AguiAgentAdapter` 构造逻辑。

`AguiRuntimeContextResolver` 可以读取 transport、path agentId、header agentId、headers、query params 和原生 Web request。

```java
@Bean
AguiRuntimeContextResolver runtimeContextResolver() {
    return request -> RuntimeContext.builder()
        .put("tenantId", request.firstHeader("X-Tenant-Id"))
        .put("traceId", request.firstHeader("X-Trace-Id"))
        .build();
}
```

`forwardedProps` 来自客户端请求体，适合传递 UI 选项或前端上下文。不要把它当作可信身份来源；服务端用户身份应由认证链路或服务端 resolver 注入。

## Frontend Tools 与合并模式

AG-UI 前端可以在 `RunAgentInput.tools` 中传入工具 schema。adapter 会在单次 run 开始时把这些工具注入 agent toolkit，并在 run 结束或取消后清理。

| `ToolMergeMode` | 行为 |
| --- | --- |
| `FRONTEND_ONLY` | 只使用前端传入工具，临时隐藏 agent 原有工具 |
| `AGENT_ONLY` | 忽略前端传入工具，只使用 agent toolkit |
| `MERGE_FRONTEND_PRIORITY` | 合并两侧工具；同名时前端工具优先 |

默认值是 `MERGE_FRONTEND_PRIORITY`。注入是 run scoped，不会永久修改 agent toolkit。

## HITL Interrupt

当模型请求工具但需要用户审批或外部执行挂起时，AG-UI adapter 会把挂起结果转换为 `RUN_FINISHED` 的 interrupt outcome：

```json
{
  "type": "RUN_FINISHED",
  "outcome": {
    "type": "interrupt",
    "interrupts": [
      {
        "reason": "tool_call",
        "toolCallId": "call-1",
        "message": "Need approval before running this tool",
        "metadata": {
          "toolName": "request_approval"
        }
      }
    ]
  }
}
```

前端拿到 interrupt 后可以弹出审批或外部执行 UI。用户完成操作后，当前 AgentScope Java 的恢复方式是在下一次 `runAgent` 中带回真实工具结果消息：

```json
{
  "id": "tool-msg-1",
  "role": "tool",
  "toolCallId": "call-1",
  "content": "{\"approved\":true}"
}
```

当前 `RunAgentInput` 没有官方 `resume` 字段，因此不要在文档或前端代码中依赖 `resume` 字段。

## 示例项目

完整示例见 [agentscope-examples/agui](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui)：

```bash
export DASHSCOPE_API_KEY=your-key
cd agentscope-examples/agui
mvn spring-boot:run
```

启动后访问 http://localhost:8080 查看默认前端示例。该示例展示了多 agent 路由、自定义 converter、自定义 enricher、token usage 和 HITL interrupt。
