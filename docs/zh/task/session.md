# Session（会话管理）

Session 支持 Agent 状态的持久化存储和恢复，让对话能够跨应用运行保持连续性。

---

## 核心特性

- **持久化存储**：保存 Agent、Memory 等组件状态
- **简洁 API**：直接通过 Agent 调用 `saveTo()` / `loadFrom()`
- **多种存储**：支持 JSON 文件、内存等后端
- **灵活标识**：使用简单的字符串会话 ID 或自定义 `SessionKey`

---

## 快速开始

```java
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import java.nio.file.Path;

// 1. 创建组件
InMemoryMemory memory = new InMemoryMemory();
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .build();

// 2. 创建 Session 并加载已有会话
Path sessionPath = Path.of(System.getProperty("user.home"), ".agentscope", "sessions");
Session session = new JsonSession(sessionPath);
agent.loadIfExists(session, "userId");

// 3. 使用 Agent
Msg response = agent.call(userMsg).block();

// 4. 保存会话
agent.saveTo(session, "userId");
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

// 创建 JsonSession
Path sessionPath = Path.of("/path/to/sessions");
Session session = new JsonSession(sessionPath);

// 保存会话
agent.saveTo(session, "user123");

// 加载会话（会话不存在时静默跳过）
agent.loadIfExists(session, "user123");

// 加载会话（会话不存在时抛异常）
agent.loadFrom(session, "user123");
```

**特性**：
- 目录格式：每个会话一个目录 `{sessionId}/`
- 单值状态：`{key}.json` 文件
- 列表状态：`{key}.jsonl` 文件（JSONL 格式，增量追加）
- UTF-8 编码，自动创建目录

> **⚠️ 安全提示**：`JsonSession` 会直接将 `sessionId` 作为会话目录名。如果 `sessionId` 来自不受信任的来源（例如 HTTP Cookie 或查询参数），攻击者可能会注入路径遍历字符（如 `..`）或路径分隔符，从而在预期的会话目录之外读写文件。**请务必在使用前验证和清理 `sessionId`** - 仅允许安全字符（字母、数字、下划线、连字符），并拒绝包含路径分隔符或 `..` 序列的值。

### InMemorySession

将状态存储在内存中，适合测试和单进程临时场景。

```java
import io.agentscope.core.session.InMemorySession;

// 创建内存会话（通常作为单例使用）
InMemorySession session = new InMemorySession();

// 保存
agent.saveTo(session, "user123");

// 加载
agent.loadIfExists(session, "user123");

// 管理功能
session.listSessionKeys();  // 获取所有会话 Key
```

**注意**：
- 应用重启后状态丢失
- 不适合分布式环境
- 内存使用随会话数量增长

---

## Agent 状态管理 API

### 保存操作

```java
// 保存会话状态
agent.saveTo(session, "sessionId");
```

### 加载操作

```java
// 加载会话（会话不存在时静默跳过，返回 false）
boolean loaded = agent.loadIfExists(session, "sessionId");

// 加载会话（会话不存在时抛异常）
agent.loadFrom(session, "sessionId");
```

### Session 管理操作

```java
import io.agentscope.core.state.SimpleSessionKey;

// 检查会话是否存在
boolean exists = session.exists(SimpleSessionKey.of("sessionId"));

// 删除会话
session.delete(SimpleSessionKey.of("sessionId"));

// 列出所有会话
Set<SessionKey> keys = session.listSessionKeys();
```

---

## 完整示例

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import java.nio.file.Path;

public class SessionExample {
    public static void main(String[] args) {
        // 创建组件
        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent = ReActAgent.builder()
            .name("Assistant")
            .model(model)
            .memory(memory)
            .build();

        // 设置会话
        String sessionId = "user_session_001";
        Path sessionPath = Path.of(System.getProperty("user.home"), ".agentscope", "sessions");
        Session session = new JsonSession(sessionPath);

        // 加载已有会话（如存在）
        if (agent.loadIfExists(session, sessionId)) {
            System.out.println("已加载会话: " + sessionId);
        } else {
            System.out.println("新建会话: " + sessionId);
        }

        // 使用 Agent
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder().text("Hello!").build())
            .build();
        Msg response = agent.call(userMsg).block();

        // 保存会话
        agent.saveTo(session, sessionId);
        System.out.println("会话已保存");
    }
}
```

---

## 自定义 Session

实现 `Session` 接口创建自定义存储后端：

```java
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DatabaseSession implements Session {
    @Override
    public void save(SessionKey sessionKey, String key, State value) {
        // 保存单个状态到数据库
    }

    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        // 保存状态列表到数据库
    }

    @Override
    public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        // 从数据库获取单个状态
        return Optional.empty();
    }

    @Override
    public <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType) {
        // 从数据库获取状态列表
        return List.of();
    }

    @Override
    public boolean exists(SessionKey sessionKey) {
        // 检查会话是否存在
        return false;
    }

    @Override
    public void delete(SessionKey sessionKey) {
        // 删除会话
    }

    @Override
    public Set<SessionKey> listSessionKeys() {
        // 列出所有会话
        return Set.of();
    }

    @Override
    public void close() {
        // 关闭连接
    }
}

// 使用
Session session = new DatabaseSession(dbConnection);
agent.saveTo(session, "user123");
```

---

## 数据格式与迁移

### 新旧数据格式对比

新版 Session API 采用了全新的存储格式，与旧版 `SessionManager` 不兼容：

| 特性 | 旧版格式 (SessionManager) | 新版格式 (saveTo/loadFrom) |
|------|--------------------------|---------------------------|
| 存储结构 | 单个 JSON 文件 | 目录 + 多个文件 |
| 文件路径 | `sessions/{sessionId}.json` | `sessions/{sessionId}/` 目录 |
| 状态存储 | 嵌套的 Map 结构 | 独立的类型化文件 |
| 列表数据 | JSON 数组 | JSONL 格式（增量追加） |

#### 旧版格式示例

```
sessions/
└── user123.json          # 单个 JSON 文件包含所有状态
```

文件内容：
```json
{
  "agent": {
    "name": "Assistant",
    "iteration": 5
  },
  "memory": {
    "messages": [
      {"role": "user", "content": "Hello"},
      {"role": "assistant", "content": "Hi!"}
    ]
  }
}
```

#### 新版格式示例

```
sessions/
└── user123/              # 每个会话一个目录
    ├── memory_messages.jsonl    # 消息列表（JSONL 格式）
    └── plan_notebook.json       # 计划状态（JSON 格式）
```

`memory_messages.jsonl` 内容（每行一条消息）：
```json
{"role":"USER","name":"user","content":[{"type":"text","text":"Hello"}]}
{"role":"ASSISTANT","name":"Assistant","content":[{"type":"text","text":"Hi!"}]}
```

### 数据迁移

由于格式不兼容，**旧版数据无法直接被新版 API 读取**。如需迁移，请按以下步骤操作：

#### 方案一：重新开始（推荐）

如果旧数据不重要，直接删除旧的会话文件，使用新 API 创建新会话：

```bash
# 删除旧的会话文件
rm -rf ~/.agentscope/sessions/*.json
```

#### 方案二：手动迁移

如果需要保留历史对话数据，可以编写迁移脚本：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.session.JsonSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SessionMigration {
    public static void migrate(Path oldSessionFile, Path newSessionDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // 1. 读取旧格式数据
        String json = Files.readString(oldSessionFile);
        Map<String, Object> oldData = mapper.readValue(json, Map.class);

        // 2. 提取消息历史
        Map<String, Object> memoryData = (Map<String, Object>) oldData.get("memory");
        List<Map<String, Object>> messages = (List<Map<String, Object>>) memoryData.get("messages");

        // 3. 创建新的 Memory 并填充数据
        InMemoryMemory memory = new InMemoryMemory();
        for (Map<String, Object> msgData : messages) {
            // 根据旧数据结构构建 Msg 对象
            Msg msg = convertToMsg(msgData);
            memory.addMessage(msg);
        }

        // 4. 使用新 API 保存
        JsonSession session = new JsonSession(newSessionDir);
        String sessionId = oldSessionFile.getFileName().toString().replace(".json", "");
        memory.saveTo(session, sessionId);

        System.out.println("迁移完成: " + sessionId);
    }

    private static Msg convertToMsg(Map<String, Object> msgData) {
        // 根据实际的旧数据结构实现转换逻辑
        // ...
    }
}
```

#### 方案三：双版本并行

在过渡期间，可以保留旧代码用于读取历史数据，新会话使用新 API：

```java
// 检查是否有旧格式数据
Path oldFile = sessionPath.resolve(sessionId + ".json");
Path newDir = sessionPath.resolve(sessionId);

if (Files.exists(newDir)) {
    // 使用新 API 加载
    agent.loadIfExists(session, sessionId);
} else if (Files.exists(oldFile)) {
    // 旧格式存在，执行迁移
    migrateOldSession(oldFile, sessionPath);
    agent.loadIfExists(session, sessionId);
}
```

### 数据库后端迁移

#### MySQL 表结构变更

新版 API 使用了不同的表结构。如果之前使用过 `MysqlSession`，需要迁移表结构：

**旧表结构**：
```sql
CREATE TABLE agentscope_sessions (
    session_id VARCHAR(255) PRIMARY KEY,
    state_data JSON NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

**新表结构**：
```sql
CREATE TABLE agentscope_sessions (
    session_id VARCHAR(255) NOT NULL,
    state_key VARCHAR(255) NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

`item_index` 列实现了真正的增量列表存储：
- 单值状态：使用 `item_index = 0` 存储
- 列表状态：每个元素单独存储一行，`item_index = 0, 1, 2, ...`

**迁移步骤**：

1. **备份旧数据**：
```sql
CREATE TABLE agentscope_sessions_backup AS SELECT * FROM agentscope_sessions;
```

2. **删除旧表**：
```sql
DROP TABLE agentscope_sessions;
```

3. **重新创建**（使用 `createIfNotExist=true` 自动创建）：
```java
MysqlSession session = new MysqlSession(dataSource, true);
```

或手动执行上述新表结构 SQL。

#### Redis 存储结构变更

新版 API 使用了不同的 Redis key 结构：

**旧结构**：
```
agentscope:session:{sessionId}  -> JSON string (所有状态)
```

**新结构**：
```
agentscope:session:{sessionId}:{stateKey}       -> JSON string (单值状态)
agentscope:session:{sessionId}:{stateKey}:list  -> Redis List (列表状态)
agentscope:session:{sessionId}:_keys            -> Redis Set (状态索引)
```

**迁移步骤**：

1. **清除旧数据**（如果不需要保留）：
```bash
redis-cli KEYS "agentscope:session:*" | xargs redis-cli DEL
```

2. 新数据会自动使用新结构存储。

---

## 更多资源

- **完整示例**: [SessionExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/SessionExample.java)
- **State 文档**: [state.md](./state.md)
- **Agent 配置**: [agent-config.md](./agent-config.md)
