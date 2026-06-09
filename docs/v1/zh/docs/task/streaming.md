# 流式输出（Streaming）

`Agent.stream()` 返回一条 `Flux<Event>` 响应式事件流，让调用方可以**实时**看到每一步推理、工具结果和最终回复，而不必等 `call()` 返回整体 `Msg`。

---

## 基础用法

```java
// ReActAgent / HarnessAgent 均可使用
Flux<Event> events = agent.stream(
        List.of(Msg.builder().role(MsgRole.USER).textContent("分析这段日志").build()),
        StreamOptions.defaults());

// 订阅并打印每个事件
events.subscribe(event ->
    System.out.printf("[%s|last=%s] %s%n",
            event.getType(), event.isLast(),
            event.getMessage().getTextContent()));

// 阻塞收集（测试 / 批处理场景）
List<Event> all = events.collectList().block();
```

带 `RuntimeContext`（harness 场景）：

```java
RuntimeContext ctx = RuntimeContext.builder()
        .sessionId("my-session").userId("alice").build();

Flux<Event> events = agent.stream(msgs, StreamOptions.defaults(), ctx);
```

`StreamOptions.defaults()` 开启**全部事件类型**、**增量模式**。

---

## 事件类型（EventType）

| 类型 | 触发时机 | 典型内容 |
|------|---------|---------|
| `REASONING` | 每个推理轮次（可含多个 chunk） | 模型文本 / 思考链 / tool_use 调用 |
| `TOOL_RESULT` | 每次工具执行完毕后 | `ToolResultBlock`（含工具名、id、输出） |
| `HINT` | RAG / 记忆检索注入后 | 注入模型的上下文文本 |
| `SUMMARY` | 达到 `maxIters` 上限时 | 迭代摘要文本 |
| `AGENT_RESULT` | 最终回复就绪 | 与 `call()` 返回值相同，默认**不在**流中 |
| `ALL` | 占位符，代表全部（不含 `AGENT_RESULT`） | — |

### 只订阅指定类型

```java
StreamOptions options = StreamOptions.builder()
        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
        .build();
```

### 区分 chunk 与最终结果

同一条 `REASONING` 消息会先推若干**增量 chunk**，最后推带完整文本的**最终事件**。
用 `Event.isLast()` 区分：

```java
events.filter(e -> e.getType() == EventType.REASONING)
      .subscribe(e -> {
          if (e.isLast()) {
              System.out.println("推理完成: " + e.getMessage().getTextContent());
          } else {
              System.out.print(e.getMessage().getTextContent());  // 实时打印 delta
          }
      });
```

---

## 增量模式 vs. 全量模式

| `incremental` | 行为 |
|--------------|------|
| `true`（默认） | 每个 chunk 只含**新增文本**（delta），消费方自行拼接 |
| `false` | 每个 chunk 携带**截至当前的全量文本**，可直接渲染 |

```java
StreamOptions options = StreamOptions.builder()
        .incremental(false)   // UI 直接渲染场景推荐
        .build();
```

---

## 推理 / 摘要内容过滤

某些模型在 `REASONING` 事件中会同时包含**思考过程 chunk** 和**最终推理结论**，可分别控制：

```java
StreamOptions options = StreamOptions.builder()
        .eventTypes(EventType.REASONING)
        .includeReasoningChunk(false)   // 不要中间 delta，只要最终结论
        .includeReasoningResult(true)
        .build();
```

摘要类似：

```java
StreamOptions options = StreamOptions.builder()
        .eventTypes(EventType.SUMMARY)
        .includeSummaryChunk(false)
        .includeSummaryResult(true)
        .build();
```

---

## 错误处理

`stream()` 遵循标准 Reactor 语义，使用 `onErrorResume` 捕获：

```java
events.onErrorResume(e -> {
    log.error("流出错", e);
    return Flux.empty();
}).subscribe(...);
```

工具执行失败时，框架将异常转为 `TOOL_RESULT` 错误文本，不会向流发送 `onError`，父流不会被提前终止。

---

## 与 Spring WebFlux / SSE 集成

`Flux<Event>` 可直接桥接 Server-Sent Events：

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String message) {
    return agent.stream(
                    List.of(Msg.builder().role(MsgRole.USER).textContent(message).build()),
                    StreamOptions.defaults())
            .map(event -> ServerSentEvent.<String>builder()
                    .event(event.getType().name().toLowerCase())
                    .data(event.getMessage().getTextContent())
                    .build());
}
```

如果使用 `HarnessAgent` 并需要向前端传递子 agent 来源信息，可将 `event.getSource()` 一并序列化（详见 [Harness 子 Agent 流式](../harness/streaming.md)）。

---

## StreamOptions 完整参数

```java
StreamOptions options = StreamOptions.builder()
        // 订阅的事件类型（默认 ALL，不含 AGENT_RESULT）
        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)

        // 增量模式（默认 true）
        .incremental(true)

        // 推理内容过滤（默认均 true）
        .includeReasoningChunk(true)
        .includeReasoningResult(true)

        // 摘要内容过滤（默认均 true）
        .includeSummaryChunk(true)
        .includeSummaryResult(true)

        .build();
```

---

## 相关文档

- [Hook](./hook.md) — 在推理 / 工具调用各阶段插入自定义逻辑
- [模型（Model）](./model.md) — 底层流式模型配置
- [Harness 子 Agent 流式](../harness/streaming.md) — HarnessAgent `agent_spawn` / `agent_send` 触发的子 agent 事件转发与 `EventSource` 字段
