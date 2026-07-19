# AG-UI

`agentscope-extensions-agui` 消费 `ReActAgent.streamEvents(...)` 提供的 2.0 细粒度事件流，并把它转成 [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) 事件，让前端 UI（Vercel AG-UI、自研 Chat UI 等）可以直接渲染 Agent 的运行过程，包括文本、工具调用、思考内容（ThinkingBlock）。

## 何时使用

- 需要把 Agent 接入对接 AG-UI 协议的前端组件库。
- 想让用户在 UI 上看到工具调用过程、模型思考过程的"打字机"流式展示。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-agui</artifactId>
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
    .enableReasoning(true)        // 输出 ThinkingBlock 为 REASONING_* 事件
    .runTimeout(Duration.ofMinutes(5))
    .build();

AguiAgentAdapter adapter = new AguiAgentAdapter(reactAgent, config);

// 前端通过 SSE 拿到的事件
Flux<AguiEvent> events = adapter.run(runAgentInput);
```

`reactAgent` 必须是 `ReActAgent`——适配器通过 `ReActAgent.streamEvents(...)` 消费其 2.0 细粒度事件流。`runAgentInput` 由前端传过来（含 `threadId`、`runId`、`messages` 等），适配器内部完成消息转换、调用 `streamEvents`，再把得到的 `AgentEvent` 映射到 AG-UI。

## 事件映射

| AgentScope `AgentEvent` | AG-UI 事件 |
| --- | --- |
| `TextBlockStartEvent` / `TextBlockDeltaEvent` / `TextBlockEndEvent` | `TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT` / `TEXT_MESSAGE_END` |
| `ThinkingBlockStartEvent` / `ThinkingBlockDeltaEvent` / `ThinkingBlockEndEvent` | `REASONING_MESSAGE_START` / `REASONING_MESSAGE_CONTENT` / `REASONING_MESSAGE_END`（需 `enableReasoning=true`） |
| `ToolCallStartEvent` / `ToolCallDeltaEvent` / `ToolCallEndEvent` | `TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END` |
| `ToolResultStartEvent` / `ToolResultTextDeltaEvent` / `ToolResultDataDeltaEvent` / `ToolResultEndEvent` | `TOOL_CALL_RESULT`（按工具调用缓冲；若该工具调用未显式开启，会防御性地补发 `TOOL_CALL_START` / `TOOL_CALL_END`） |

## 与 Spring Boot 集成

通常做法是写一个 `@PostMapping("/ag-ui")` 把 `RunAgentInput` 映射到 `Flux<AguiEvent>` 并以 SSE 响应。也可以使用 `agentscope-agui-spring-boot-starter` 自动注册控制器。

## 常用配置

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `toolMergeMode` | `MERGE_FRONTEND_PRIORITY` | 前端定义的 tool 与 Agent 内置 tool 的合并策略 |
| `emitStateEvents` | `true` | 是否输出 `STATE_*` 事件（如 thread 状态） |
| `emitToolCallArgs` | `true` | 是否流式发送工具调用参数 |
| `enableReasoning` | `false` | 是否把 ThinkingBlock 输出为 REASONING_* 事件 |
| `runTimeout` | `10m` | 单次运行超时时间 |
| `defaultAgentId` | `null` | 没有显式 `agentId` 时使用的默认值 |
