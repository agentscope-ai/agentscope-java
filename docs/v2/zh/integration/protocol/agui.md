# AG-UI

`agentscope-extensions-agui` 把 AgentScope 的事件流转成 [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) 事件，让前端 UI（Vercel AG-UI、自研 Chat UI 等）可以直接渲染 Agent 的运行过程，包括文本、工具调用、思考内容（ThinkingBlock）。

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

AguiAgentAdapter adapter = new AguiAgentAdapter(agent, config);

// 前端通过 SSE 拿到的事件
Flux<AguiEvent> events = adapter.run(runAgentInput);
```

`runAgentInput` 由前端传过来（含 `threadId`、`runId`、`messages` 等），适配器内部完成消息转换、调用 Agent 流式 API，再把事件映射到 AG-UI。

## 事件映射

| AgentScope 事件 / 块 | AG-UI 事件 |
| --- | --- |
| `EventType.REASONING / SUMMARY` 中的 `TextBlock` | `TEXT_MESSAGE_*` |
| `EventType.REASONING / SUMMARY` 中的 `ThinkingBlock` | `REASONING_*`（需 `enableReasoning=true`） |
| `ToolUseBlock` | `TOOL_CALL_START` |
| `EventType.TOOL_RESULT` | `TOOL_CALL_END` |

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
