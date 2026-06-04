# 子 Agent 流式输出

> **前置阅读**：[流式输出基础用法](../task/streaming.md) — `stream()` API、`EventType`、`StreamOptions`、SSE 集成。本页专注 `HarnessAgent` 的**子 agent 事件转发**机制。

当父 `HarnessAgent.stream()` 期间调用了 `agent_spawn` 或 `agent_send`，子 agent 产生的**所有中间事件**会被实时注入父 `Flux<Event>`，并携带 `EventSource` 标识来源，无需任何额外配置。

---

## 工作原理

```
AgentBase.createEventStream()
  │
  ├─ 创建 FluxSink<Event>
  ├─ 构造 SubagentEventBus（实现 = sink::next）
  └─ 通过 Reactor Context 注入 bus
        │
        ▼
  ReActAgent 推理循环（运行在 Reactor Context 中）
        │
        ▼  acting 阶段调用 agent_spawn
  AgentSpawnTool.execLocalSync()
        │
        ├─ 从 Reactor Context 读取 SubagentEventBus
        ├─ 调用 DefaultAgentManager.invokeAgentStream()
        │       │  子 HarnessAgent.stream()
        │       │    每个子 Event ──map(withSource)──▶ 打 EventSource 标签
        │       └─ Flux<Event> 流回 execLocalSync
        │
        └─ doOnNext(bus::emit) ──▶ 每个子 Event 实时推入父 FluxSink
```

**关键约束**：仅 `stream()` 模式有 bus；`call()` 模式 bus 缺席，自动降级为阻塞 `invokeAgent`，行为不变。

---

## 事件时序

```
调用方
  └─ parent.stream()
        │
        ├─ REASONING(parent, chunk×N)   ← 父推理第一轮（含 tool_use）
        │
        │  [agent_spawn "researcher" 开始]
        ├─ REASONING(child, chunk×M)    ← 子推理（实时转发，带 EventSource）
        ├─ TOOL_RESULT(child, ...)      ← 子工具结果（如有，实时转发）
        ├─ REASONING(child, last)       ← 子推理最终结果（实时转发）
        ├─ AGENT_RESULT(child, last)    ← 子最终回复（实时转发）
        │  [agent_spawn 结束，子结果作为 TOOL_RESULT 返回父]
        │
        ├─ TOOL_RESULT(parent, ...)     ← 父收到子 agent 返回
        ├─ REASONING(parent, chunk×K)   ← 父推理第二轮
        └─ AGENT_RESULT(parent, last)   ← 父最终回复
```

父 agent 自身事件 `source == null`；子 agent 事件 `source != null`。

---

## 通过 EventSource 区分来源

```java
Flux<Event> events = parent.stream(msgs, StreamOptions.defaults(), ctx);

events.subscribe(event -> {
    EventSource src = event.getSource();
    if (src == null) {
        // 父 agent 自身事件
        System.out.printf("[parent][%s] %s%n",
                event.getType(), event.getMessage().getTextContent());
    } else {
        // 子（或孙）agent 事件
        System.out.printf("[%s|depth=%d|path=%s][%s] %s%n",
                src.getAgentId(), src.getDepth(), src.getPath(),
                event.getType(), event.getMessage().getTextContent());
    }
});
```

### EventSource 字段一览

| 字段 | 含义 | 示例值 |
|------|------|--------|
| `agentKey` | 运行时实例句柄，可传给 `agent_send` | `agent:researcher:550e8400-…` |
| `agentId` | 子 agent 类型 ID（`subagents/<id>.md` 文件名） | `researcher` |
| `agentName` | 子 agent 显示名（可空） | `ResearcherAgent` |
| `sessionId` | 本次调用的独立会话 ID | `sub-a1b2c3d4-…` |
| `parentSessionId` | 父 agent 的会话 ID | `sess-main-001` |
| `depth` | 嵌套深度（父直接子 = 1，孙 = 2，…） | `1` |
| `path` | 以 `/` 分隔的调用路径 | `sess-main-001/researcher` |
| `taskId` | 保留，现阶段 null（异步流预留） | `null` |

**path 规则**：格式为 `<parentSessionId>/<agentId>`，多级嵌套时自然叠加，如 `sess-001/planner/executor`。

---

## 多级嵌套（孙 Agent）

子 `HarnessAgent` 在自己的 `stream()` 入口也会注入一个**新 bus**。孙 agent 的事件先被子 agent 的 bus 捕获（depth+1），再随子 agent 的 `Flux<Event>` 出口流出，被祖父的 `AgentSpawnTool` 再次转发到祖父 bus。path 按深度自动拼接。

```
parent.stream()
  └─ child.stream()             depth=1, path="sess/planner"
        └─ grandchild.stream()  depth=2, path="sess/planner/executor"
```

消费方只需过滤 `source.getDepth()` 或 `source.getPath()` 前缀即可定位任意层级：

```java
// 仅取第一层子 agent 的 REASONING 事件
events.filter(e -> e.getSource() != null
               && e.getSource().getDepth() == 1
               && e.getType() == EventType.REASONING)
      .subscribe(...);

// 仅取路径包含 "executor" 的事件（任意深度）
events.filter(e -> e.getSource() != null
               && e.getSource().getPath().contains("executor"))
      .subscribe(...);
```

---

## 常用消费模式

### 1. 实时流式 UI 渲染

按事件类型分流，分别送入不同 UI 组件：

```java
events.groupBy(e -> e.getSource() == null ? "parent" : e.getSource().getAgentId())
      .flatMap(group -> group.doOnNext(e -> renderToPanel(group.key(), e)))
      .subscribe();
```

### 2. 等待子 agent 最终结果

如果只关心子 agent 的回复文本（不关心中间事件）：

```java
String childReply = events
        .filter(e -> e.getSource() != null
                  && "researcher".equals(e.getSource().getAgentId())
                  && e.getType() == EventType.AGENT_RESULT
                  && e.isLast())
        .map(e -> e.getMessage().getTextContent())
        .blockFirst();
```

### 3. 收集并按层级分组

```java
Map<String, List<Event>> byAgent = events.collectList().block()
        .stream()
        .collect(Collectors.groupingBy(e ->
                e.getSource() == null ? "parent" : e.getSource().getAgentId()));
```

### 4. SSE 转发（含来源元数据）

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String message,
                                          @RequestParam String sessionId) {
    RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
    return agent.stream(
                    List.of(Msg.builder().role(MsgRole.USER).textContent(message).build()),
                    StreamOptions.defaults(), ctx)
            .map(event -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", event.getType());
                payload.put("text", event.getMessage().getTextContent());
                payload.put("last", event.isLast());
                if (event.getSource() != null) {
                    payload.put("agentId", event.getSource().getAgentId());
                    payload.put("depth",   event.getSource().getDepth());
                    payload.put("path",    event.getSource().getPath());
                }
                return ServerSentEvent.<String>builder()
                        .data(objectMapper.writeValueAsString(payload))
                        .build();
            });
}
```

---

## 错误处理

子 agent 内部发生异常时，框架**捕获后写入 `TOOL_RESULT` 错误文本**，不向父 `Flux` 传播 `onError`，父流不受影响。

若整条父流发生致命错误（如模型调用失败），则遵循标准 Reactor 语义：

```java
events.onErrorResume(e -> {
    log.error("父流出错", e);
    return Flux.empty();
}).subscribe(...);
```

---

## 行为边界

| 场景 | 行为 |
|------|------|
| `stream()` + 同步本地子 agent（`timeout_seconds > 0`） | ✔ 子 agent 事件实时转发，携带 `EventSource` |
| `call()` 模式（非流式） | ✔ 正常阻塞调用，子结果以 `tool_result` 字符串返回；无事件转发 |
| `timeout_seconds=0` 后台异步任务 | ✗ 暂不支持流式转发（后续扩展点） |
| 远程 subagent（Agent Protocol） | ✗ 暂不支持流式转发（后续扩展点） |
| 多级嵌套（孙 agent） | ✔ 自动按深度 path 叠加，`FluxSink.next` 线程安全 |

---

## 相关文档

- [流式输出基础](../task/streaming.md) — `stream()` API、`EventType`、`StreamOptions` 完整参数
- [子 Agent（Subagent）](./subagent.md) — 子 agent 声明、`agent_spawn` / `agent_send` 参数
- [架构（Architecture）](./architecture.md) — `SubagentEventBus`、Reactor Context 注入与 `StreamingHook` 时序
