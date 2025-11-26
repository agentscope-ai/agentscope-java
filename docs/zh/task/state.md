# 状态与会话管理

AgentScope 提供两个层级的状态管理：

- **Session（会话）**（推荐）：用于跨应用运行管理状态的高级 API
- **State（状态）**（高级）：用于细粒度控制的底层 API

本指南重点介绍 Session API，这是大多数使用场景的推荐方法。

## 什么是会话？

**Session（会话）** 为状态组件（智能体、内存、工具包）提供跨应用运行的持久化存储。它允许您：

- 保存和恢复完整的应用状态
- 从中断处继续对话
- 统一管理多个组件
- 在环境之间迁移状态

## 快速开始

### 保存状态

使用 `SessionManager` 保存智能体和内存状态：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.session.SessionManager;
import java.nio.file.Path;

// 创建并使用智能体
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

agent.call(msg1).block();
agent.call(msg2).block();

// 保存会话
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .addComponent(agent)
        .saveSession();
```

### 加载状态

从保存的会话恢复智能体：

```java
// 使用相同配置创建智能体
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

// 如果会话存在则加载
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .addComponent(agent)
        .loadIfExists();

// 智能体从中断处继续
agent.call(msg3).block();
```

## 多组件会话

会话可以同时管理多个组件，保留它们之间的关系：

```java
import io.agentscope.core.tool.Toolkit;

// 创建组件
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .build();

InMemoryMemory memory = new InMemoryMemory();
Toolkit toolkit = new Toolkit();

// 将所有组件一起保存
SessionManager.forSessionId("conversation-001")
        .withJsonSession(Path.of("./sessions"))
        .addComponent(agent)
        .addComponent(memory)
        .addComponent(toolkit)
        .saveSession();

// 稍后，加载所有组件
SessionManager.forSessionId("conversation-001")
        .withJsonSession(Path.of("./sessions"))
        .addComponent(agent)
        .addComponent(memory)
        .addComponent(toolkit)
        .loadIfExists();
```

## 会话操作

### 检查会话是否存在

```java
boolean exists = SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .sessionExists();

if (exists) {
    System.out.println("找到会话");
}
```

### 带错误处理的加载

```java
try {
    // 如果会话不存在则抛出异常
    SessionManager.forSessionId("user123")
            .withJsonSession(Path.of("sessions"))
            .addComponent(agent)
            .loadOrThrow();
} catch (IllegalArgumentException e) {
    System.err.println("未找到会话: " + e.getMessage());
}
```

### 条件保存

```java
// 仅在会话已存在时保存
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .addComponent(agent)
        .saveIfExists();
```

### 删除会话

```java
// 如果存在则删除
boolean deleted = SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .deleteIfExists();

// 或者如果不存在则抛出异常
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .deleteOrThrow();
```

## 会话存储

### JsonSession（默认）

`JsonSession` 将状态作为 JSON 文件存储在文件系统中：

- **默认位置**：`~/.agentscope/sessions/`
- **文件格式**：每个会话一个 JSON 文件（以会话 ID 命名）
- **功能**：自动创建目录、UTF-8 编码、格式化输出

```java
import io.agentscope.core.session.SessionManager;

// 使用默认位置（~/.agentscope/sessions/）
SessionManager.forSessionId("user123")
        .withDefaultJsonSession()
        .addComponent(agent)
        .saveSession();

// 使用自定义位置
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("/path/to/sessions"))
        .addComponent(agent)
        .saveSession();
```

### 自定义会话后端

您可以通过扩展 `SessionBase` 实现自定义会话后端：

```java
import io.agentscope.core.session.SessionBase;

// 示例：基于数据库的会话
SessionManager.forSessionId("user123")
        .withSession(() -> new DatabaseSession(dbConnection))
        .addComponent(agent)
        .saveSession();
```

## 组件命名

组件会自动使用其 `getComponentName()` 方法或类名进行命名：

```java
// 自动命名
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .addComponent(agent)      // 命名为 "reActAgent"
        .addComponent(memory)     // 命名为 "inMemoryMemory"
        .saveSession();
```

会话文件结构：
```json
{
  "reActAgent": {
    "agentId": "...",
    "memory": {...}
  },
  "inMemoryMemory": {
    "messages": [...]
  }
}
```

## 高级：底层状态 API

对于细粒度控制，您可以使用底层的 `StateModule` 接口：

### 手动状态管理

```java
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

// 手动保存状态
Map<String, Object> state = agent.saveState();

// 序列化为 JSON
ObjectMapper mapper = new ObjectMapper();
String jsonState = mapper.writeValueAsString(state);

// 保存到文件
Files.writeString(Path.of("agent-state.json"), jsonState);

// 手动加载状态
String jsonState = Files.readString(Path.of("agent-state.json"));
Map<String, Object> state = mapper.readValue(jsonState, Map.class);

// 恢复智能体
agent.loadState(state);
```

### 何时使用底层 API

在以下情况下使用底层 API：
- 自定义序列化格式（非 JSON）
- 与现有存储系统集成
- 部分状态更新
- 加载/保存期间的状态转换

## 完整示例

```java
package io.agentscope.tutorial.task;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.SessionManager;
import java.nio.file.Path;
import java.util.List;

public class SessionExample {
    public static void main(String[] args) {
        // 会话 ID（例如，用户 ID、对话 ID）
        String sessionId = "user-alice-chat-001";
        Path sessionPath = Path.of("./sessions");

        // 创建模型
        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .build();

        // 创建智能体
        ReActAgent agent = ReActAgent.builder()
                .name("Assistant")
                .sysPrompt("你是一个有帮助的助手。记住之前的对话。")
                .model(model)
                .memory(new InMemoryMemory())
                .build();

        // 尝试加载现有会话
        SessionManager.forSessionId(sessionId)
                .withJsonSession(sessionPath)
                .addComponent(agent)
                .loadIfExists();

        // 与智能体交互
        Msg userMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder()
                        .text("你好！我的名字是 Alice。")
                        .build()))
                .build();

        Msg response = agent.call(userMsg).block();
        System.out.println("智能体: " + response.getTextContent());

        // 保存会话
        SessionManager.forSessionId(sessionId)
                .withJsonSession(sessionPath)
                .addComponent(agent)
                .saveSession();

        System.out.println("会话已保存到: " + sessionPath.resolve(sessionId + ".json"));
    }
}
```

## 最佳实践

1. **使用有意义的会话 ID**：使用用户 ID、对话 ID 或其他标识符
   ```java
   // 好的做法
   SessionManager.forSessionId("user-123-conversation-456")

   // 避免
   SessionManager.forSessionId("session1")
   ```

2. **定期保存**：在重要操作后持久化状态
   ```java
   agent.call(criticalMsg).block();
   sessionManager.saveSession();  // 立即保存
   ```

3. **使用 `loadIfExists()` 实现优雅启动**：不要在会话不存在时失败
   ```java
   // 优雅 - 如果未找到则创建新会话
   SessionManager.forSessionId(sessionId)
           .withJsonSession(path)
           .addComponent(agent)
           .loadIfExists();
   ```

4. **清理旧会话**：删除不再需要的会话
   ```java
   if (userLoggedOut) {
       SessionManager.forSessionId(sessionId)
               .withJsonSession(path)
               .deleteIfExists();
   }
   ```

5. **处理错误**：对关键操作使用 try-catch
   ```java
   try {
       SessionManager.forSessionId(sessionId)
               .withJsonSession(path)
               .addComponent(agent)
               .saveOrThrow();
   } catch (Exception e) {
       logger.error("保存会话失败", e);
       // 处理错误（重试、通知用户等）
   }
   ```

## 故障排除

### 会话未加载

**问题**：`loadIfExists()` 未恢复状态

**解决方案**：
- 验证会话文件是否存在：`sessionManager.sessionExists()`
- 检查保存和加载之间的组件名称是否匹配
- 确保使用相同的组件类型

### 状态损坏

**问题**：会话加载但智能体行为异常

**解决方案**：
- 验证会话文件格式（应为有效 JSON）
- 检查保存的代码版本和当前代码版本是否不匹配
- 如果损坏则删除并重新创建会话

### 文件权限错误

**问题**：无法写入会话目录

**解决方案**：
- 检查目录权限
- 确保父目录存在
- 使用绝对路径而不是相对路径

## 下一步

- [内存](memory.md) - 理解内存管理
- [智能体](../quickstart/agent.md) - 构建有状态的智能体
- [核心概念](../quickstart/key-concepts.md) - 学习 Session 概念

## 参考

- [SessionManager.java](https://github.com/agentscope-ai/agentscope-java/blob/main/src/main/java/io/agentscope/core/session/SessionManager.java)
- [JsonSession.java](https://github.com/agentscope-ai/agentscope-java/blob/main/src/main/java/io/agentscope/core/session/JsonSession.java)
- [StateModule.java](https://github.com/agentscope-ai/agentscope-java/blob/main/src/main/java/io/agentscope/core/state/StateModule.java)
