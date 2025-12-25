# Session（会话管理）

Session 支持 Agent 状态的持久化存储和恢复，让对话能够跨应用运行保持连续性。

---

## 核心特性

- **持久化存储**：保存 Agent、Memory、Toolkit 等组件状态
- **自动命名**：组件自动命名，无需硬编码字符串
- **流式 API**：链式调用简化操作
- **多种存储**：支持 JSON 文件、内存等后端

---

## 快速开始

```java
import io.agentscope.core.session.SessionManager;
import java.nio.file.Path;

// 1. 创建组件
InMemoryMemory memory = new InMemoryMemory();
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .build();

// 2. 创建 SessionManager 并加载已有会话
SessionManager sessionManager = SessionManager.forSessionId("userId")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .addComponent(memory);

sessionManager.loadIfExists();

// 3. 使用 Agent
Msg response = agent.call(userMsg).block();

// 4. 保存会话
sessionManager.saveSession();
```

---

## Session 实现

AgentScope 提供两种 Session 实现：

| 实现 | 持久化 | 适用场景 |
|------|--------|---------|
| `JsonSession` | 文件系统 | 生产环境、跨重启持久化 |
| `InMemorySession` | 内存 | 测试、单进程临时存储 |

### JsonSession（推荐）

将状态以 JSON 文件存储在文件系统中。

```java
import io.agentscope.core.session.JsonSession;

// 方式 1：指定路径
SessionManager.forSessionId("user123")
    .withJsonSession(Path.of("/path/to/sessions"))
    .addComponent(agent)
    .saveSession();

// 方式 2：使用默认路径（~/.agentscope/sessions/）
SessionManager.forSessionId("user123")
    .withDefaultJsonSession()
    .addComponent(agent)
    .saveSession();

// 方式 3：直接传入实例
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .saveSession();
```

**特性**：
- 文件格式：`{sessionId}.json`
- UTF-8 编码，格式化输出
- 自动创建目录
- 原子性写入（临时文件 + 重命名）

### InMemorySession

将状态存储在内存中，适合测试和单进程临时场景。

```java
import io.agentscope.core.session.InMemorySession;

// 创建内存会话（通常作为单例使用）
InMemorySession session = new InMemorySession();

// 保存
SessionManager.forSessionId("user123")
    .withSession(session)
    .addComponent(agent)
    .saveSession();

// 加载
SessionManager.forSessionId("user123")
    .withSession(session)
    .addComponent(agent)
    .loadIfExists();

// 管理功能
session.getSessionCount();  // 获取会话数量
session.clearAll();         // 清除所有会话
```

**注意**：
- 应用重启后状态丢失
- 不适合分布式环境
- 内存使用随会话数量增长

---

## SessionManager API

### 保存操作

```java
sessionManager.saveSession();      // 保存（覆盖已有）
sessionManager.saveIfExists();     // 仅当会话已存在时保存
sessionManager.saveOrThrow();      // 保存失败时抛异常
```

### 加载操作

```java
sessionManager.loadIfExists();     // 会话不存在时静默跳过
sessionManager.loadOrThrow();      // 会话不存在时抛异常
```

### 其他操作

```java
sessionManager.sessionExists();    // 检查会话是否存在
sessionManager.deleteIfExists();   // 删除会话（如存在）
sessionManager.deleteOrThrow();    // 删除会话（不存在时抛异常）
```

---

## 会话列表管理

```java
import io.agentscope.core.session.SessionInfo;

JsonSession session = new JsonSession(sessionPath);

// 列出所有会话
List<String> sessionIds = session.listSessions();

// 获取会话信息
for (String sessionId : sessionIds) {
    SessionInfo info = session.getSessionInfo(sessionId);
    System.out.println("会话: " + sessionId);
    System.out.println("  大小: " + info.getSize() + " bytes");
    System.out.println("  组件数: " + info.getComponentCount());
}
```

---

## 自定义 Session

实现 `Session` 接口创建自定义存储后端：

```java
import io.agentscope.core.session.Session;

public class DatabaseSession implements Session {
    @Override
    public void saveSessionState(String sessionId, Map<String, StateModule> stateModules) {
        // 保存到数据库
    }

    @Override
    public void loadSessionState(String sessionId, boolean allowNotExist,
                                  Map<String, StateModule> stateModules) {
        // 从数据库加载
    }

    // 实现其他方法...
}

// 使用
SessionManager.forSessionId("user123")
    .withSession(new DatabaseSession(dbConnection))
    .addComponent(agent)
    .saveSession();
```

---

## 更多资源

- **完整示例**: [SessionExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/SessionExample.java)
- **State 文档**: [state.md](./state.md)
- **Agent 配置**: [agent-config.md](./agent-config.md)
