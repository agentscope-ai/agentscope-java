# MsgHub（消息中心）

MsgHub 是 AgentScope 中用于多智能体对话的消息广播中心。它管理一组智能体之间的消息分发，无需手动编写消息传递代码。

## 概述

在构建多智能体应用时，您经常需要让智能体相互通信。如果不使用 MsgHub，您需要手动在智能体之间传递消息：

```java
// 不使用 MsgHub（冗长且容易出错）
Msg aliceReply = alice.call().block();
bob.observe(aliceReply).block();
charlie.observe(aliceReply).block();

Msg bobReply = bob.call().block();
alice.observe(bobReply).block();
charlie.observe(bobReply).block();
```

使用 MsgHub，这变得简单多了：

```java
// 使用 MsgHub（简洁且自动化）
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob, charlie)
        .build()) {
    hub.enter().block();

    alice.call().block();  // Bob 和 Charlie 自动收到
    bob.call().block();    // Alice 和 Charlie 自动收到
}
```

## 核心特性

- **自动广播**：任何参与者的消息都会自动广播给所有其他参与者
- **动态参与者**：可以在对话过程中添加或移除智能体
- **公告消息**：进入 Hub 时发送初始消息
- **手动广播**：需要时可以手动广播消息
- **生命周期管理**：使用 try-with-resources 自动清理

## 基本用法

### 创建和使用 MsgHub

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.pipeline.MsgHub;

// 创建模型，使用 MultiAgentFormatter
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .formatter(new DashScopeMultiAgentFormatter())
        .build();

// 创建智能体
ReActAgent alice = ReActAgent.builder()
        .name("Alice")
        .sysPrompt("你是 Alice，一位友好的老师。回答请简洁。")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

ReActAgent bob = ReActAgent.builder()
        .name("Bob")
        .sysPrompt("你是 Bob，一位好奇的学生。回答请简洁。")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

ReActAgent charlie = ReActAgent.builder()
        .name("Charlie")
        .sysPrompt("你是 Charlie，一位深思熟虑的观察者。回答请简洁。")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

// 创建公告消息
Msg announcement = Msg.builder()
        .name("system")
        .role(MsgRole.SYSTEM)
        .content(TextBlock.builder()
                .text("欢迎来到讨论！请简短地介绍一下自己。")
                .build())
        .build();

// 使用 try-with-resources 管理 MsgHub
try (MsgHub hub = MsgHub.builder()
        .name("Introduction")
        .participants(alice, bob, charlie)
        .announcement(announcement)
        .enableAutoBroadcast(true)  // 默认为 true
        .build()) {

    // 进入 Hub（向所有参与者广播公告）
    hub.enter().block();

    // 每个智能体自我介绍
    // 他们的回复会自动广播给其他人
    Msg aliceReply = alice.call().block();
    System.out.println("Alice: " + aliceReply.getTextContent());

    Msg bobReply = bob.call().block();
    System.out.println("Bob: " + bobReply.getTextContent());

    Msg charlieReply = charlie.call().block();
    System.out.println("Charlie: " + charlieReply.getTextContent());
}
// Hub 自动关闭，订阅者被清理
```

> **重要提示**：使用 MsgHub 时，请使用 `DashScopeMultiAgentFormatter`（或其他提供商的对应格式化器）而不是标准格式化器。这种格式化器可以正确处理来自不同名称的多个智能体的消息。

### 生命周期方法

MsgHub 遵循 enter/exit 生命周期：

```java
MsgHub hub = MsgHub.builder()
        .participants(alice, bob)
        .build();

// Enter：设置订阅并广播公告
hub.enter().block();

// ... 对话进行 ...

// Exit：清理订阅
hub.exit().block();
```

使用 try-with-resources 时，`close()` 会被自动调用，它内部会调用 `exit()`。

## 动态参与者管理

您可以在对话过程中添加或移除参与者：

### 添加参与者

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob)
        .build()) {

    hub.enter().block();

    // Alice 和 Bob 对话
    alice.call().block();
    bob.call().block();

    // 对话中途添加 Charlie
    hub.add(charlie).block();

    // 现在 Charlie 也会收到消息
    alice.call().block();  // Charlie 会收到
    charlie.call().block(); // Alice 和 Bob 会收到
}
```

### 移除参与者

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob, charlie)
        .build()) {

    hub.enter().block();

    // 三人对话
    alice.call().block();
    bob.call().block();
    charlie.call().block();

    // 将 Bob 从对话中移除
    hub.delete(bob).block();

    // Bob 不会收到这些消息
    alice.call().block();
    charlie.call().block();
}
```

> **注意**：新添加的参与者不会收到之前的消息。他们只会收到加入后的消息。

## 手动广播

您可以禁用自动广播并手动控制消息分发：

### 禁用自动广播

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob, charlie)
        .enableAutoBroadcast(false)  // 禁用自动广播
        .build()) {

    hub.enter().block();

    // Alice 说话，但其他人不会自动收到
    Msg aliceReply = alice.call().block();

    // 手动广播给所有参与者
    hub.broadcast(aliceReply).block();

    // 现在 Bob 和 Charlie 已经收到 Alice 的消息
    bob.call().block();
}
```

### 切换自动广播

您可以在对话过程中切换自动广播模式：

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob)
        .enableAutoBroadcast(true)
        .build()) {

    hub.enter().block();

    // 自动广播开启
    alice.call().block();  // Bob 自动收到

    // 关闭自动广播
    hub.setAutoBroadcast(false);

    Msg bobReply = bob.call().block();
    // Alice 不会自动收到 Bob 的回复

    // 手动广播特定消息
    hub.broadcast(bobReply).block();

    // 重新开启自动广播
    hub.setAutoBroadcast(true);
}
```

### 广播多条消息

```java
List<Msg> messages = List.of(msg1, msg2, msg3);
hub.broadcast(messages).block();
```

## 响应式编程风格

MsgHub 支持使用 Project Reactor 进行完全响应式编程：

```java
MsgHub hub = MsgHub.builder()
        .participants(alice, bob, charlie)
        .announcement(announcement)
        .build();

// 完全响应式链
hub.enter()
    .then(alice.call())
    .doOnSuccess(msg -> System.out.println("Alice: " + msg.getTextContent()))
    .then(bob.call())
    .doOnSuccess(msg -> System.out.println("Bob: " + msg.getTextContent()))
    .then(charlie.call())
    .doOnSuccess(msg -> System.out.println("Charlie: " + msg.getTextContent()))
    .then(hub.exit())
    .block();  // 只在最后阻塞一次
```

## API 参考

### Builder 方法

| 方法 | 描述 | 默认值 |
|------|------|--------|
| `name(String)` | 设置 Hub 名称 | UUID |
| `participants(AgentBase...)` | 设置参与者（必需） | - |
| `participants(List<AgentBase>)` | 从列表设置参与者 | - |
| `announcement(Msg...)` | 设置公告消息 | 无 |
| `announcement(List<Msg>)` | 从列表设置公告 | 无 |
| `enableAutoBroadcast(boolean)` | 启用/禁用自动广播 | `true` |

### 实例方法

| 方法 | 返回类型 | 描述 |
|------|----------|------|
| `enter()` | `Mono<MsgHub>` | 进入 Hub 上下文，设置订阅 |
| `exit()` | `Mono<Void>` | 退出 Hub 上下文，清理订阅 |
| `close()` | `void` | AutoCloseable 实现 |
| `add(AgentBase...)` | `Mono<Void>` | 添加新参与者 |
| `add(List<AgentBase>)` | `Mono<Void>` | 从列表添加参与者 |
| `delete(AgentBase...)` | `Mono<Void>` | 移除参与者 |
| `delete(List<AgentBase>)` | `Mono<Void>` | 从列表移除参与者 |
| `broadcast(Msg)` | `Mono<Void>` | 广播单条消息 |
| `broadcast(List<Msg>)` | `Mono<Void>` | 广播多条消息 |
| `setAutoBroadcast(boolean)` | `void` | 切换自动广播 |
| `getName()` | `String` | 获取 Hub 名称 |
| `getParticipants()` | `List<AgentBase>` | 获取当前参与者 |
| `isAutoBroadcastEnabled()` | `boolean` | 检查自动广播状态 |

## 相关文档

- [Pipeline](./pipeline.md) - 顺序和并行智能体执行
- [多智能体辩论](./multiagent-debate.md) - 辩论工作流模式
