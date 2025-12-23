# Session（会话管理）

Session 支持 Agent 状态的持久化存储和恢复，让对话能够跨应用运行保持连续性。

---

## 核心特性

- **持久化存储**：保存 Agent、Memory、Toolkit 等组件状态
- **自动命名**：组件自动命名，无需硬编码字符串
- **流式 API**：链式调用简化操作
- **多种存储**：支持 JSON 文件、数据库等后端

---

## 快速开始

### 基本用法

```java
import io.agentscope.core.session.SessionManager;
import java.nio.file.Path;
import java.nio.file.Paths;

// 1. 创建组件
InMemoryMemory memory = new InMemoryMemory();
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .build();

// 2. 创建 SessionManager
Path sessionPath = Paths.get(System.getProperty("user.home"), 
                            ".agentscope", "sessions");

SessionManager sessionManager = SessionManager.forSessionId("userId")
    .withJsonSession(sessionPath)
    .addComponent(agent)    // 自动命名为 "agent"
    .addComponent(memory);  // 自动命名为 "memory"

// 3. 加载已有会话（如果存在）
sessionManager.loadIfExists();

// 4. 使用 Agent
Msg userMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder().text("你好").build())
    .build();

Msg response = agent.call(userMsg).block();

// 5. 保存会话
sessionManager.saveSession();
```

---

## SessionManager API

### 创建方式

```java
// 方式 1: 使用 JsonSession（推荐）
SessionManager.forSessionId("session_id")
    .withJsonSession(Path.of("sessions"))
    .addComponent(agent);

// 方式 2: 使用默认路径
SessionManager.forSessionId("session_id")
    .withDefaultJsonSession()  // 使用 "sessions" 目录
    .addComponent(agent);

// 方式 3: 自定义 Session 实现
SessionManager.forSessionId("session_id")
    .withSession(() -> new DatabaseSession(db))
    .addComponent(agent);
```

---

## JsonSession

基于 JSON 文件的会话实现，每个会话一个 JSON 文件。

```java
import io.agentscope.core.session.JsonSession;

// 默认路径：~/.agentscope/sessions
JsonSession session = new JsonSession();

// 自定义路径
JsonSession session = new JsonSession(Path.of("/path/to/sessions"));
```

**特性**：
- 文件格式：`{sessionId}.json`
- UTF-8 编码，格式化输出
- 自动创建目录
- 原子性写入（临时文件 + 重命名）

---

## 完整示例

```java
package io.agentscope.examples;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.SessionManager;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SessionExample {
    
    public static void main(String[] args) throws Exception {
        String sessionId = "userId";
        Path sessionPath = Paths.get(System.getProperty("user.home"),
                ".agentscope", "examples", "sessions");

        // 创建组件
        InMemoryMemory memory = new InMemoryMemory();
        Toolkit toolkit = new Toolkit();

        ReActAgent agent = ReActAgent.builder()
                .name("Assistant")
                .sysPrompt("You are a helpful AI assistant with persistent memory.")
                .toolkit(toolkit)
                .memory(memory)
                .model(DashScopeChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        .modelName("qwen3-max")
                        .stream(true)
                        .build())
                .build();

        // 创建 SessionManager
        SessionManager sessionManager = SessionManager.forSessionId(sessionId)
                .withJsonSession(sessionPath)
                .addComponent(agent)
                .addComponent(memory);

        // 加载已有会话
        if (sessionManager.sessionExists()) {
            sessionManager.loadIfExists();
            System.out.println("Session loaded: " + sessionId
                    + " (" + memory.getMessages().size() + " messages)");
        } else {
            System.out.println("New session created: " + sessionId);
        }

        // 交互
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("My name is Alice").build())
                .build();

        Msg response = agent.call(userMsg).block();
        System.out.println("Agent> " + response.getTextContent());

        // 保存会话
        sessionManager.saveSession();
        System.out.println("Session saved");
    }
}
```

**运行效果**：

第一次运行：
```
New session created: userId
Agent> Nice to meet you, Alice! How can I assist you today?
Session saved
```

第二次运行：
```
Session loaded: userId (2 messages)
Agent> Hello Alice! It's good to see you again. Is there something specific you need help with today?
Session saved
```

---

## 高级用法

### 多用户会话

```java
String userId = getCurrentUserId();
SessionManager sessionManager = SessionManager.forSessionId(userId)
    .withJsonSession(sessionPath)
    .addComponent(agent)
    .addComponent(memory);
```

### 会话列表

```java
import io.agentscope.core.session.SessionInfo;
import java.util.List;

JsonSession session = new JsonSession(sessionPath);
List<String> sessionIds = session.listSessions();

for (String sessionId : sessionIds) {
    SessionInfo info = session.getSessionInfo(sessionId);
    System.out.println("会话: " + sessionId);
    System.out.println("  大小: " + info.getSize() + " bytes");
    System.out.println("  组件数: " + info.getComponentCount());
}
```

### 自定义组件持久化

```java
import io.agentscope.core.state.StateModule;
import java.util.HashMap;
import java.util.Map;

public class CustomComponent implements StateModule {
    private String customData;
    
    @Override
    public Map<String, Object> stateDict() {
        Map<String, Object> state = new HashMap<>();
        state.put("customData", customData);
        return state;
    }
    
    @Override
    public void loadStateDict(Map<String, Object> state, boolean strict) {
        if (state.containsKey("customData")) {
            this.customData = (String) state.get("customData");
        }
    }
    
    @Override
    public String getComponentName() {
        return "customComponent";
    }
}

// 使用
sessionManager.addComponent(new CustomComponent());
```

### 错误处理

```java
// 加载
try {
    sessionManager.loadIfExists();
} catch (Exception e) {
    System.err.println("Failed to load: " + e.getMessage());
}

// 保存
try {
    sessionManager.saveSession();
} catch (Exception e) {
    System.err.println("Failed to save: " + e.getMessage());
    e.printStackTrace();
}
```

---

## 更多资源

- **完整示例**: [SessionExample.java](../../examples/src/main/java/io/agentscope/examples/SessionExample.java)
- **State 文档**: [state.md](./state.md)
- **Agent 配置**: [agent-config.md](./agent-config.md)
