# Kotlin Extensions

`agentscope-extensions-kotlin` provides `suspend` functions and `Flow`-style APIs so Kotlin code doesn't have to deal with Reactor's `Mono / Flux` directly.

## When to use

- Your project is primarily Kotlin and you want idiomatic coroutine code.
- You'd like to `collect` the streaming events as a `Flow<Event>`.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-kotlin</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Suspend-style call

```kotlin
import io.agentscope.kotlin.callSuspend

suspend fun ask(agent: Agent, msg: Msg): Msg = agent.callSuspend(msg)
```

`callSuspend` calls `agent.call(...)` and `awaitSingle()`s. Overloads cover:

```kotlin
agent.callSuspend(msg)
agent.callSuspend(msgs)
agent.callSuspend()                         // no input, reuse memory
agent.callSuspend(msg, structuredModel)     // structured output
agent.callSuspend(msgs, structuredModel)
agent.callSuspend(structuredModel)
```

## Flow-style stream

```kotlin
import io.agentscope.kotlin.streamFlow

agent.streamFlow(msg).collect { event ->
    when (event.type) {
        EventType.REASONING -> println(event.payload)
        EventType.TOOL_RESULT -> println("tool: $event")
        else -> {}
    }
}

// Custom StreamOptions
agent.streamFlow(msgs, StreamOptions.incremental()).collect { ... }

// Structured output
agent.streamFlow(msg, StreamOptions.defaults(), MyDto::class.java).collect { ... }
```

`streamFlow(...)` is `agent.stream(...).asFlow()` and depends on `kotlinx-coroutines-reactor`.

## observe

```kotlin
agent.observeSuspend(msg)    // equivalent to agent.observe(msg).awaitFirstOrNull()
```

> The module is purely a Kotlin-friendly facade; behavior matches the Java API. Java projects don't need this dependency.
