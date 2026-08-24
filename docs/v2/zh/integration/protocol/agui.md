# AG-UI

## 兼容性说明

`agentscope-extensions-agui` 把 AgentScope v2 的 `AgentEvent` 流转换为 [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) 事件，让前端 UI 可以实时渲染 agent 的运行过程，包括文本、推理内容、工具调用、状态、自定义事件、token usage 和 HITL interrupt。

`RUN_ERROR` 和 `RUN_FINISHED` 是互斥终态事件。只有还需要旧版 `RUN_ERROR` + `RUN_FINISHED` 序列时，才开启 `emitRunFinishedAfterError=true`。

`AguiMessage.content` 现在使用类型化消息内容表示。仅处理纯文本时，请使用 `getTextContent()`。

已支持多模态输入，但是暂不支持文档类型。

`AguiMessageConverter.toAguiMessage()` 目前只保留文本和工具调用字段；image、audio、video、document 内容块不会被序列化回 AG-UI message content。

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

正常运行的 `RUN_STARTED` 和 `RUN_FINISHED` 由上游 `AgentStartEvent` / `AgentEndEvent` 决定。正常流结束但上游没有发 `AgentEndEvent` 时，adapter 不会额外补 `RUN_FINISHED`。异常路径会输出带 `timestamp` 的 `RUN_ERROR`。 `RUN_ERROR` 和 `RUN_FINISHED` 是互斥终态事件。只有旧客户端仍依赖错误后补发完成事件时，才设置 `emitRunFinishedAfterError=true`（Spring Boot 配置为 `agentscope.agui.emit-run-finished-after-error=true`）。

## 子 agent 事件

默认（`emitSubagentEventsAsNative=false`）下，带非空 `source` 的 AgentEvent（子 / 远程子 agent 事件）**不会**映射为原生的 `TEXT_MESSAGE_*` / `RUN_*` / 工具调用事件，而是变成 `subagent.*` 命名空间下的 AG-UI `CUSTOM` 事件，避免污染父 run 的生命周期与文本流：

| CUSTOM `name` | 典型 AgentEvent |
| --- | --- |
| `subagent.lifecycle` | `AgentStartEvent` / `AgentEndEvent` |
| `subagent.text` | `TextBlockDeltaEvent` |
| `subagent.thinking` | `ThinkingBlockDeltaEvent` |
| `subagent.tool_call` | `ToolCallStartEvent` / `ToolCallEndEvent` |
| `subagent.tool_result` | `ToolResultEndEvent` |
| `subagent.require_confirm` | `RequireUserConfirmEvent` |

payload 至少包含 `source` 与 `type`（以及 `delta`、`toolCallId` 等类型相关字段）。

若要恢复子事件与父事件使用同一套原生 converter 的旧行为：

```java
AguiAdapterConfig config = AguiAdapterConfig.builder()
    .emitSubagentEventsAsNative(true)
    .build();
```

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
| `agui.resume` | `RunAgentInput.resume` |

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
    emit-run-finished-after-error: false
    server-side-memory: false
    interrupt-on-disconnect: true
```

`interrupt-on-disconnect` 用于控制 MVC/WebFlux 的 SSE 连接关闭、超时或发送事件失败时是否中断
Agent run。默认值为 `true`，用于保持现有行为兼容。设置为 `false` 后，客户端断开时 Agent
会继续执行；连接关闭期间产生的事件不会由 starter 重放。

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

当一次 run 因工具决策暂停时，AG-UI adapter 会在 `RUN_FINISHED` 上输出官方 interrupt outcome。AgentScope Java 内置了两类 tool-call interrupt 路径：

- **工具挂起 / 外部执行**：挂起的 `ToolResultBlock` 会转换成 `tool_call` interrupt，恢复时桥接回 `ToolResultBlock`。
- **权限确认**：`RequireUserConfirmEvent` 会转换成带 AgentScope metadata 的 `tool_call` interrupt，恢复时桥接为 `ConfirmResult`。

这两类场景都使用官方 AG-UI `reason: "tool_call"`，因为 interrupt 绑定到具体 `toolCallId`。不要把这类工具审批写成 `reason: "confirmation"`。

```json
{
  "type": "RUN_FINISHED",
  "outcome": {
    "type": "interrupt",
    "interrupts": [
      {
        "id": "reply-1:call-1",
        "reason": "tool_call",
        "toolCallId": "call-1",
        "message": "Need approval before running this tool",
        "responseSchema": {
          "type": "object",
          "properties": {
            "approved": { "type": "boolean" },
            "editedArgs": {
              "type": "object",
              "description": "Full replacement of the tool args. Not merged."
            }
          },
          "required": ["approved"]
        },
        "metadata": {
          "agentscope.interruptKind": "permission_confirm",
          "toolName": "request_approval",
          "toolInput": { "path": "/tmp/report.txt" },
          "toolContent": "{\"path\":\"/tmp/report.txt\"}",
          "replyId": "reply-1"
        }
      }
    ]
  }
}
```

前端拿到 interrupt 后可以弹出审批或外部执行 UI。用户完成操作后，在同一个 `threadId` 的下一次 `runAgent` 请求中带回官方 `resume[]`：

```json
{
  "threadId": "thread-1",
  "runId": "run-2",
  "messages": [],
  "resume": [
    {
      "interruptId": "reply-1:call-1",
      "status": "resolved",
      "payload": {
        "approved": true,
        "editedArgs": {
          "path": "/tmp/reviewed-report.txt"
        }
      }
    }
  ]
}
```

`status` 支持官方的 `resolved` 和 `cancelled`。对于用户拒绝某个工具请求的常见审批场景，建议仍使用 `resolved`，并在 `payload` 中表达业务决策，例如 `{ "approved": false }`；`cancelled` 更适合表示该 interrupt 本身被取消。

对于权限确认，只有 `payload.approved` 是布尔值 `true` 时才会批准工具；缺失、非布尔值或 `false` 都会视为拒绝。`payload.editedArgs` 如果存在，必须是 JSON object，并且是对原始工具参数的**完整替换**，不是局部 merge。AgentScope Java 会根据 `editedArgs` 同时重建 `ToolUseBlock.input` 和原始 JSON `ToolUseBlock.content`，因此被批准的工具会使用修改后的参数执行。

前端不需要在 `resume[]` 中回传 `metadata`；只需要发送 `interruptId`、`status` 和 `payload`。通过 Spring `AguiRequestProcessor` 入口时，AgentScope Java 会在服务端记录最近一次 `RUN_FINISHED.outcome.interrupts[]`，校验下一次 `resume[]` 是否覆盖所有 open interrupts，并把原始 interrupt 传给 adapter 做恢复转换。

## 示例项目

完整示例见 [agentscope-examples/agui](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui)：

```bash
export DASHSCOPE_API_KEY=your-key
cd agentscope-examples/agui
mvn spring-boot:run
```

启动后访问 http://localhost:8080 查看默认前端示例。该示例展示了多 agent 路由、自定义 converter、自定义 enricher、token usage 和 HITL interrupt。
