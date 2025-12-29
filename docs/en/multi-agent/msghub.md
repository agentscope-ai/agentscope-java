# MsgHub

MsgHub is a message broadcasting center for multi-agent conversations in AgentScope. It manages message distribution among a group of agents, eliminating the need for manual message passing code.

## Overview

When building multi-agent applications, you often need agents to communicate with each other. Without MsgHub, you would have to manually pass messages between agents:

```java
// Without MsgHub (verbose and error-prone)
Msg aliceReply = alice.call().block();
bob.observe(aliceReply).block();
charlie.observe(aliceReply).block();

Msg bobReply = bob.call().block();
alice.observe(bobReply).block();
charlie.observe(bobReply).block();
```

With MsgHub, this becomes much simpler:

```java
// With MsgHub (clean and automatic)
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob, charlie)
        .build()) {
    hub.enter().block();

    alice.call().block();  // Bob and Charlie automatically receive this
    bob.call().block();    // Alice and Charlie automatically receive this
}
```

## Core Features

- **Automatic Broadcasting**: Messages from any participant are automatically broadcast to all other participants
- **Dynamic Participants**: Add or remove agents during conversation
- **Announcement Messages**: Send initial messages when entering the hub
- **Manual Broadcasting**: Broadcast messages manually when needed
- **Lifecycle Management**: Automatic cleanup with try-with-resources

## Basic Usage

### Creating and Using MsgHub

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.pipeline.MsgHub;

// Create model with MultiAgentFormatter (important!)
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .formatter(new DashScopeMultiAgentFormatter())
        .build();

// Create agents
ReActAgent alice = ReActAgent.builder()
        .name("Alice")
        .sysPrompt("You are Alice, a friendly teacher. Be brief in your responses.")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

ReActAgent bob = ReActAgent.builder()
        .name("Bob")
        .sysPrompt("You are Bob, a curious student. Be brief in your responses.")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

ReActAgent charlie = ReActAgent.builder()
        .name("Charlie")
        .sysPrompt("You are Charlie, a thoughtful observer. Be brief in your responses.")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

// Create announcement message
Msg announcement = Msg.builder()
        .name("system")
        .role(MsgRole.SYSTEM)
        .content(TextBlock.builder()
                .text("Welcome to the discussion! Please introduce yourself briefly.")
                .build())
        .build();

// Use MsgHub with try-with-resources
try (MsgHub hub = MsgHub.builder()
        .name("Introduction")
        .participants(alice, bob, charlie)
        .announcement(announcement)
        .enableAutoBroadcast(true)  // Default is true
        .build()) {

    // Enter the hub (broadcasts announcement to all participants)
    hub.enter().block();

    // Each agent introduces themselves
    // Their responses are automatically broadcast to others
    Msg aliceReply = alice.call().block();
    System.out.println("Alice: " + aliceReply.getTextContent());

    Msg bobReply = bob.call().block();
    System.out.println("Bob: " + bobReply.getTextContent());

    Msg charlieReply = charlie.call().block();
    System.out.println("Charlie: " + charlieReply.getTextContent());
}
// Hub is automatically closed, subscribers are cleaned up
```

> **Important**: When using MsgHub, use `DashScopeMultiAgentFormatter` (or equivalent for other providers) instead of the standard formatter. This formatter properly handles messages from multiple agents with different names.

### Lifecycle Methods

MsgHub follows an enter/exit lifecycle:

```java
MsgHub hub = MsgHub.builder()
        .participants(alice, bob)
        .build();

// Enter: sets up subscriptions and broadcasts announcements
hub.enter().block();

// ... conversation happens ...

// Exit: cleans up subscriptions
hub.exit().block();
```

When using try-with-resources, `close()` is called automatically, which internally calls `exit()`.

## Dynamic Participant Management

You can add or remove participants during a conversation:

### Adding Participants

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob)
        .build()) {

    hub.enter().block();

    // Alice and Bob talk
    alice.call().block();
    bob.call().block();

    // Add Charlie mid-conversation
    hub.add(charlie).block();

    // Now Charlie receives messages too
    alice.call().block();  // Charlie receives this
    charlie.call().block(); // Alice and Bob receive this
}
```

### Removing Participants

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob, charlie)
        .build()) {

    hub.enter().block();

    // All three talk
    alice.call().block();
    bob.call().block();
    charlie.call().block();

    // Remove Bob from the conversation
    hub.delete(bob).block();

    // Bob won't receive these messages
    alice.call().block();
    charlie.call().block();
}
```

> **Note**: Newly added participants will NOT receive previous messages. They only receive messages from the point they join.

## Manual Broadcasting

You can disable automatic broadcasting and manually control message distribution:

### Disabling Auto-Broadcast

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob, charlie)
        .enableAutoBroadcast(false)  // Disable automatic broadcasting
        .build()) {

    hub.enter().block();

    // Alice speaks, but others don't automatically receive it
    Msg aliceReply = alice.call().block();

    // Manually broadcast to all participants
    hub.broadcast(aliceReply).block();

    // Now Bob and Charlie have received Alice's message
    bob.call().block();
}
```

### Toggling Auto-Broadcast

You can toggle auto-broadcast mode during conversation:

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob)
        .enableAutoBroadcast(true)
        .build()) {

    hub.enter().block();

    // Auto-broadcast is on
    alice.call().block();  // Bob automatically receives

    // Turn off auto-broadcast
    hub.setAutoBroadcast(false);

    Msg bobReply = bob.call().block();
    // Alice doesn't automatically receive Bob's reply

    // Manually broadcast specific messages
    hub.broadcast(bobReply).block();

    // Turn auto-broadcast back on
    hub.setAutoBroadcast(true);
}
```

### Broadcasting Multiple Messages

```java
List<Msg> messages = List.of(msg1, msg2, msg3);
hub.broadcast(messages).block();
```

## Reactive Programming Style

MsgHub supports fully reactive programming with Project Reactor:

```java
MsgHub hub = MsgHub.builder()
        .participants(alice, bob, charlie)
        .announcement(announcement)
        .build();

// Fully reactive chain
hub.enter()
    .then(alice.call())
    .doOnSuccess(msg -> System.out.println("Alice: " + msg.getTextContent()))
    .then(bob.call())
    .doOnSuccess(msg -> System.out.println("Bob: " + msg.getTextContent()))
    .then(charlie.call())
    .doOnSuccess(msg -> System.out.println("Charlie: " + msg.getTextContent()))
    .then(hub.exit())
    .block();  // Only block once at the end
```

## API Reference

### Builder Methods

| Method | Description | Default |
|--------|-------------|---------|
| `name(String)` | Set hub name | UUID |
| `participants(AgentBase...)` | Set participants (required) | - |
| `participants(List<AgentBase>)` | Set participants from list | - |
| `announcement(Msg...)` | Set announcement messages | None |
| `announcement(List<Msg>)` | Set announcements from list | None |
| `enableAutoBroadcast(boolean)` | Enable/disable auto-broadcast | `true` |

### Instance Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `enter()` | `Mono<MsgHub>` | Enter hub context, setup subscriptions |
| `exit()` | `Mono<Void>` | Exit hub context, cleanup subscriptions |
| `close()` | `void` | AutoCloseable implementation |
| `add(AgentBase...)` | `Mono<Void>` | Add new participants |
| `add(List<AgentBase>)` | `Mono<Void>` | Add participants from list |
| `delete(AgentBase...)` | `Mono<Void>` | Remove participants |
| `delete(List<AgentBase>)` | `Mono<Void>` | Remove participants from list |
| `broadcast(Msg)` | `Mono<Void>` | Broadcast single message |
| `broadcast(List<Msg>)` | `Mono<Void>` | Broadcast multiple messages |
| `setAutoBroadcast(boolean)` | `void` | Toggle auto-broadcast |
| `getName()` | `String` | Get hub name |
| `getParticipants()` | `List<AgentBase>` | Get current participants |
| `isAutoBroadcastEnabled()` | `boolean` | Check auto-broadcast status |

## Related Documentation

- [Pipeline](./pipeline.md) - Sequential and parallel agent execution
- [Multi-Agent Debate](./multiagent-debate.md) - Debate workflow pattern
