# Session

Session enables persistent storage and recovery of Agent state, allowing conversations to maintain continuity across application runs.

---

## Core Features

- **Persistent Storage**: Save Agent, Memory, and other component states
- **Simple API**: Call `saveTo()` / `loadFrom()` directly on Agent
- **Multiple Backends**: Supports JSON files, in-memory, and custom storage
- **Flexible Identification**: Use simple string session IDs or custom `SessionKey`

---

## Quick Start

```java
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import java.nio.file.Path;

// 1. Create components
InMemoryMemory memory = new InMemoryMemory();
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .build();

// 2. Create Session and load existing session
Path sessionPath = Path.of(System.getProperty("user.home"), ".agentscope", "sessions");
Session session = new JsonSession(sessionPath);
agent.loadIfExists(session, "userId");

// 3. Use Agent
Msg response = agent.call(userMsg).block();

// 4. Save session
agent.saveTo(session, "userId");
```

---

## Session Implementations

AgentScope provides two Session implementations:

| Implementation | Persistence | Use Case |
|---------------|-------------|----------|
| `JsonSession` | File system | Production, cross-restart persistence |
| `InMemorySession` | Memory | Testing, single-process temporary storage |

### JsonSession (Recommended)

Stores state as JSON files on the filesystem.

```java
import io.agentscope.core.session.JsonSession;

// Create JsonSession
Path sessionPath = Path.of("/path/to/sessions");
Session session = new JsonSession(sessionPath);

// Save session
agent.saveTo(session, "user123");

// Load session (silently skip if doesn't exist)
agent.loadIfExists(session, "user123");

// Load session (throw exception if doesn't exist)
agent.loadFrom(session, "user123");
```

**Features**:
- Directory format: One directory per session `{sessionId}/`
- Single states: `{key}.json` files
- List states: `{key}.jsonl` files (JSONL format, incremental append)
- UTF-8 encoding, automatic directory creation

> **⚠️ Security Note**: `JsonSession` uses the `sessionId` directly as the session directory name. If `sessionId` comes from untrusted sources (e.g., HTTP cookies or query parameters), an attacker could inject path traversal characters like `..` or path separators to read/write files outside the intended session directory. **Always validate and sanitize `sessionId` before use** - only allow safe characters (alphanumeric, underscore, hyphen) and reject values containing path separators or `..` sequences.

### InMemorySession

Stores state in memory, suitable for testing and single-process temporary scenarios.

```java
import io.agentscope.core.session.InMemorySession;

// Create in-memory session (typically used as singleton)
InMemorySession session = new InMemorySession();

// Save
agent.saveTo(session, "user123");

// Load
agent.loadIfExists(session, "user123");

// Management features
session.listSessionKeys();  // Get all session keys
```

**Notes**:
- State is lost when application restarts
- Not suitable for distributed environments
- Memory usage grows with session count

---

## Agent State Management API

### Save Operations

```java
// Save session state
agent.saveTo(session, "sessionId");
```

### Load Operations

```java
// Load session (silently skip if doesn't exist, returns false)
boolean loaded = agent.loadIfExists(session, "sessionId");

// Load session (throw exception if doesn't exist)
agent.loadFrom(session, "sessionId");
```

### Session Management Operations

```java
import io.agentscope.core.state.SimpleSessionKey;

// Check if session exists
boolean exists = session.exists(SimpleSessionKey.of("sessionId"));

// Delete session
session.delete(SimpleSessionKey.of("sessionId"));

// List all sessions
Set<SessionKey> keys = session.listSessionKeys();
```

---

## Complete Example

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
        // Create components
        InMemoryMemory memory = new InMemoryMemory();
        ReActAgent agent = ReActAgent.builder()
            .name("Assistant")
            .model(model)
            .memory(memory)
            .build();

        // Set up session
        String sessionId = "user_session_001";
        Path sessionPath = Path.of(System.getProperty("user.home"), ".agentscope", "sessions");
        Session session = new JsonSession(sessionPath);

        // Load existing session (if exists)
        if (agent.loadIfExists(session, sessionId)) {
            System.out.println("Loaded session: " + sessionId);
        } else {
            System.out.println("New session: " + sessionId);
        }

        // Use Agent
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder().text("Hello!").build())
            .build();
        Msg response = agent.call(userMsg).block();

        // Save session
        agent.saveTo(session, sessionId);
        System.out.println("Session saved");
    }
}
```

---

## Custom Session

Implement the `Session` interface to create custom storage backends:

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
        // Save single state to database
    }

    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        // Save state list to database
    }

    @Override
    public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        // Get single state from database
        return Optional.empty();
    }

    @Override
    public <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType) {
        // Get state list from database
        return List.of();
    }

    @Override
    public boolean exists(SessionKey sessionKey) {
        // Check if session exists
        return false;
    }

    @Override
    public void delete(SessionKey sessionKey) {
        // Delete session
    }

    @Override
    public Set<SessionKey> listSessionKeys() {
        // List all sessions
        return Set.of();
    }

    @Override
    public void close() {
        // Close connection
    }
}

// Usage
Session session = new DatabaseSession(dbConnection);
agent.saveTo(session, "user123");
```

---

## Data Format and Migration

### Old vs New Data Format Comparison

The new Session API uses a completely different storage format that is incompatible with the old `SessionManager` approach:

| Feature | Old Format (SessionManager) | New Format (saveTo/loadFrom) |
|---------|----------------------------|------------------------------|
| Storage Structure | Single JSON file | Directory + multiple files |
| File Path | `sessions/{sessionId}.json` | `sessions/{sessionId}/` directory |
| State Storage | Nested Map structure | Independent typed files |
| List Data | JSON array | JSONL format (incremental append) |

#### Old Format Example

```
sessions/
└── user123.json          # Single JSON file containing all state
```

File content:
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

#### New Format Example

```
sessions/
└── user123/              # One directory per session
    ├── memory_messages.jsonl    # Message list (JSONL format)
    └── plan_notebook.json       # Plan state (JSON format)
```

`memory_messages.jsonl` content (one message per line):
```json
{"role":"USER","name":"user","content":[{"type":"text","text":"Hello"}]}
{"role":"ASSISTANT","name":"Assistant","content":[{"type":"text","text":"Hi!"}]}
```

### Data Migration

Due to format incompatibility, **old data cannot be directly read by the new API**. If migration is needed, follow these steps:

#### Option 1: Start Fresh (Recommended)

If old data is not important, simply delete old session files and use the new API to create new sessions:

```bash
# Delete old session files
rm -rf ~/.agentscope/sessions/*.json
```

#### Option 2: Manual Migration

If you need to preserve historical conversation data, write a migration script:

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

        // 1. Read old format data
        String json = Files.readString(oldSessionFile);
        Map<String, Object> oldData = mapper.readValue(json, Map.class);

        // 2. Extract message history
        Map<String, Object> memoryData = (Map<String, Object>) oldData.get("memory");
        List<Map<String, Object>> messages = (List<Map<String, Object>>) memoryData.get("messages");

        // 3. Create new Memory and populate data
        InMemoryMemory memory = new InMemoryMemory();
        for (Map<String, Object> msgData : messages) {
            // Build Msg object based on old data structure
            Msg msg = convertToMsg(msgData);
            memory.addMessage(msg);
        }

        // 4. Save using new API
        JsonSession session = new JsonSession(newSessionDir);
        String sessionId = oldSessionFile.getFileName().toString().replace(".json", "");
        memory.saveTo(session, sessionId);

        System.out.println("Migration complete: " + sessionId);
    }

    private static Msg convertToMsg(Map<String, Object> msgData) {
        // Implement conversion logic based on actual old data structure
        // ...
    }
}
```

#### Option 3: Dual Version Parallel

During transition, keep old code for reading historical data while using new API for new sessions:

```java
// Check if old format data exists
Path oldFile = sessionPath.resolve(sessionId + ".json");
Path newDir = sessionPath.resolve(sessionId);

if (Files.exists(newDir)) {
    // Load using new API
    agent.loadIfExists(session, sessionId);
} else if (Files.exists(oldFile)) {
    // Old format exists, perform migration
    migrateOldSession(oldFile, sessionPath);
    agent.loadIfExists(session, sessionId);
}
```

### Database Backend Migration

#### MySQL Table Structure Change

The new API uses a different table structure. If you previously used `MysqlSession`, you need to migrate the table structure:

**Old Table Structure**:
```sql
CREATE TABLE agentscope_sessions (
    session_id VARCHAR(255) PRIMARY KEY,
    state_data JSON NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

**New Table Structure**:
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

The `item_index` column enables true incremental list storage:
- Single states: stored with `item_index = 0`
- List states: each item stored in a separate row with `item_index = 0, 1, 2, ...`

**Migration Steps**:

1. **Backup old data**:
```sql
CREATE TABLE agentscope_sessions_backup AS SELECT * FROM agentscope_sessions;
```

2. **Drop old table**:
```sql
DROP TABLE agentscope_sessions;
```

3. **Recreate** (use `createIfNotExist=true` for automatic creation):
```java
MysqlSession session = new MysqlSession(dataSource, true);
```

Or manually execute the new table structure SQL above.

#### Redis Storage Structure Change

The new API uses a different Redis key structure:

**Old Structure**:
```
agentscope:session:{sessionId}  -> JSON string (all states)
```

**New Structure**:
```
agentscope:session:{sessionId}:{stateKey}       -> JSON string (single state)
agentscope:session:{sessionId}:{stateKey}:list  -> Redis List (list state)
agentscope:session:{sessionId}:_keys            -> Redis Set (state index)
```

**Migration Steps**:

1. **Clear old data** (if not needed):
```bash
redis-cli KEYS "agentscope:session:*" | xargs redis-cli DEL
```

2. New data will automatically use the new structure.

---

## More Resources

- **Complete Example**: [SessionExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/SessionExample.java)
- **State Documentation**: [state.md](./state.md)
- **Agent Configuration**: [agent-config.md](./agent-config.md)
