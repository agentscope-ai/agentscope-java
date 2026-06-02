# Kotlin 扩展

`agentscope-extensions-kotlin` 给 Kotlin 用户提供 `suspend` 函数和 `Flow` 风格的 API，避免在 Kotlin 项目里直接面对 Reactor 的 `Mono / Flux`。

## 何时使用

- 项目主要用 Kotlin，希望写更地道的协程代码。
- 想把 `Agent.stream(...)` 直接当作 `Flow<Event>` 来 collect。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-kotlin</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## suspend 风格的 call

```kotlin
import io.agentscope.kotlin.callSuspend

suspend fun ask(agent: Agent, msg: Msg): Msg = agent.callSuspend(msg)
```

`callSuspend` 内部调用 `agent.call(...)` 然后 `awaitSingle()`。同名重载覆盖了：

```kotlin
agent.callSuspend(msg)
agent.callSuspend(msgs)
agent.callSuspend()                         // 不带输入，复用 memory
agent.callSuspend(msg, structuredModel)     // 结构化输出
agent.callSuspend(msgs, structuredModel)
agent.callSuspend(structuredModel)
```

## Flow 风格的 stream

```kotlin
import io.agentscope.kotlin.streamFlow

agent.streamFlow(msg).collect { event ->
    when (event.type) {
        EventType.REASONING -> println(event.payload)
        EventType.TOOL_RESULT -> println("tool: $event")
        else -> {}
    }
}

// 自定义 StreamOptions
agent.streamFlow(msgs, StreamOptions.incremental()).collect { ... }

// 结构化输出
agent.streamFlow(msg, StreamOptions.defaults(), MyDto::class.java).collect { ... }
```

`streamFlow(...)` 内部是 `agent.stream(...).asFlow()`，依赖 `kotlinx-coroutines-reactor`。

## observe

```kotlin
agent.observeSuspend(msg)    // 等价于 agent.observe(msg).awaitFirstOrNull()
```

> 模块只在 Kotlin 友好的 API 这一层做封装，行为与 Java 侧完全一致。Java 项目无需引入。
