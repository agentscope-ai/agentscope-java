# Session

Session enables persistent storage and recovery of Agent state, allowing conversations to maintain continuity across application runs.

---

## Core Features

- **Persistent Storage**: Save Agent, Memory, Toolkit, and other component states
- **Auto Naming**: Components are automatically named without hardcoding strings
- **Fluent API**: Chainable calls simplify operations
- **Multiple Backends**: Supports JSON files, in-memory, and custom storage

---

## Quick Start

```java
import io.agentscope.core.session.SessionManager;
import java.nio.file.Path;

// 1. Create components
InMemoryMemory memory = new InMemoryMemory();
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .build();

// 2. Create SessionManager and load existing session
SessionManager sessionManager = SessionManager.forSessionId("userId")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .addComponent(memory);

sessionManager.loadIfExists();

// 3. Use Agent
Msg response = agent.call(userMsg).block();

// 4. Save session
sessionManager.saveSession();
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

// Option 1: Specify path
SessionManager.forSessionId("user123")
    .withJsonSession(Path.of("/path/to/sessions"))
    .addComponent(agent)
    .saveSession();

// Option 2: Use default path (~/.agentscope/sessions/)
SessionManager.forSessionId("user123")
    .withDefaultJsonSession()
    .addComponent(agent)
    .saveSession();

// Option 3: Pass instance directly
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .saveSession();
```

**Features**:
- File format: `{sessionId}.json`
- UTF-8 encoding, pretty printing
- Automatic directory creation
- Atomic writes (temp file + rename)

### InMemorySession

Stores state in memory, suitable for testing and single-process temporary scenarios.

```java
import io.agentscope.core.session.InMemorySession;

// Create in-memory session (typically used as singleton)
InMemorySession session = new InMemorySession();

// Save
SessionManager.forSessionId("user123")
    .withSession(session)
    .addComponent(agent)
    .saveSession();

// Load
SessionManager.forSessionId("user123")
    .withSession(session)
    .addComponent(agent)
    .loadIfExists();

// Management features
session.getSessionCount();  // Get session count
session.clearAll();         // Clear all sessions
```

**Notes**:
- State is lost when application restarts
- Not suitable for distributed environments
- Memory usage grows with session count

---

## SessionManager API

### Save Operations

```java
sessionManager.saveSession();      // Save (overwrites existing)
sessionManager.saveIfExists();     // Save only if session already exists
sessionManager.saveOrThrow();      // Throw exception on save failure
```

### Load Operations

```java
sessionManager.loadIfExists();     // Silently skip if session doesn't exist
sessionManager.loadOrThrow();      // Throw exception if session doesn't exist
```

### Other Operations

```java
sessionManager.sessionExists();    // Check if session exists
sessionManager.deleteIfExists();   // Delete session (if exists)
sessionManager.deleteOrThrow();    // Delete session (throw if doesn't exist)
```

---

## Session List Management

```java
import io.agentscope.core.session.SessionInfo;

JsonSession session = new JsonSession(sessionPath);

// List all sessions
List<String> sessionIds = session.listSessions();

// Get session info
for (String sessionId : sessionIds) {
    SessionInfo info = session.getSessionInfo(sessionId);
    System.out.println("Session: " + sessionId);
    System.out.println("  Size: " + info.getSize() + " bytes");
    System.out.println("  Components: " + info.getComponentCount());
}
```

---

## Custom Session

Implement the `Session` interface to create custom storage backends:

```java
import io.agentscope.core.session.Session;

public class DatabaseSession implements Session {
    @Override
    public void saveSessionState(String sessionId, Map<String, StateModule> stateModules) {
        // Save to database
    }

    @Override
    public void loadSessionState(String sessionId, boolean allowNotExist,
                                  Map<String, StateModule> stateModules) {
        // Load from database
    }

    // Implement other methods...
}

// Usage
SessionManager.forSessionId("user123")
    .withSession(new DatabaseSession(dbConnection))
    .addComponent(agent)
    .saveSession();
```

---

## More Resources

- **Complete Example**: [SessionExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/SessionExample.java)
- **State Documentation**: [state.md](./state.md)
- **Agent Configuration**: [agent-config.md](./agent-config.md)
