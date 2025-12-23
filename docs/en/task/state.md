# State and Session Management

AgentScope provides two levels of state management:

- **Session** (recommended): High-level API for managing state across application runs
- **State** (advanced): Low-level API for fine-grained control

This guide focuses on the Session API, which is the recommended approach for most use cases.

## What is a Session?

A **Session** provides persistent storage for stateful components (agents, memories, toolkits) across application runs. It allows you to:

- Save and restore complete application state
- Resume conversations from where they left off
- Manage multiple components together
- Migrate state between environments

## Quick Start

### Saving State

Use `SessionManager` to save agent and memory state:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.session.SessionManager;
import java.nio.file.Path;

// Create and use agent
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

agent.call(msg1).block();
agent.call(msg2).block();

// Save session
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .addComponent(agent)
        .saveSession();
```

### Loading State

Restore agent from saved session:

```java
// Create agent with same configuration
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

// Load session if exists
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .addComponent(agent)
        .loadIfExists();

// Agent continues from where it left off
agent.call(msg3).block();
```

## Multi-Component Sessions

Sessions can manage multiple components simultaneously, preserving relationships:

```java
import io.agentscope.core.tool.Toolkit;

// Create components
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .build();

InMemoryMemory memory = new InMemoryMemory();
Toolkit toolkit = new Toolkit();

// Save all components together
SessionManager.forSessionId("conversation-001")
        .withJsonSession(Path.of("./sessions"))
        .addComponent(agent)
        .addComponent(memory)
        .addComponent(toolkit)
        .saveSession();

// Later, load all components
SessionManager.forSessionId("conversation-001")
        .withJsonSession(Path.of("./sessions"))
        .addComponent(agent)
        .addComponent(memory)
        .addComponent(toolkit)
        .loadIfExists();
```

## Session Operations

### Check if Session Exists

```java
boolean exists = SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .sessionExists();

if (exists) {
    System.out.println("Session found");
}
```

### Load with Error Handling

```java
try {
    // Throws exception if session doesn't exist
    SessionManager.forSessionId("user123")
            .withJsonSession(Path.of("sessions"))
            .addComponent(agent)
            .loadOrThrow();
} catch (IllegalArgumentException e) {
    System.err.println("Session not found: " + e.getMessage());
}
```

### Conditional Save

```java
// Only save if session already exists
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .addComponent(agent)
        .saveIfExists();
```

### Delete Session

```java
// Delete if exists
boolean deleted = SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .deleteIfExists();

// Or throw exception if doesn't exist
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .deleteOrThrow();
```

## Session Storage

### JsonSession (Default)

`JsonSession` stores state as JSON files on the filesystem:

- **Default location**: `~/.agentscope/sessions/`
- **File format**: One JSON file per session (named by session ID)
- **Features**: Automatic directory creation, UTF-8 encoding, pretty printing

```java
import io.agentscope.core.session.SessionManager;

// Use default location (~/.agentscope/sessions/)
SessionManager.forSessionId("user123")
        .withDefaultJsonSession()
        .addComponent(agent)
        .saveSession();

// Use custom location
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("/path/to/sessions"))
        .addComponent(agent)
        .saveSession();
```

### Custom Session Backends

You can implement custom session backends by extending `SessionBase`:

```java
import io.agentscope.core.session.SessionBase;

// Example: Database-backed session
SessionManager.forSessionId("user123")
        .withSession(() -> new DatabaseSession(dbConnection))
        .addComponent(agent)
        .saveSession();
```

## Component Naming

Components are automatically named using their `getComponentName()` method or class name:

```java
// Automatic naming
SessionManager.forSessionId("user123")
        .withJsonSession(Path.of("sessions"))
        .addComponent(agent)      // Named "reActAgent"
        .addComponent(memory)     // Named "inMemoryMemory"
        .saveSession();
```

The session file structure:
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

## Advanced: Low-Level State API

For fine-grained control, you can use the low-level `StateModule` interface:

### Manual State Management

```java
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

// Save state manually
Map<String, Object> state = agent.saveState();

// Serialize to JSON
ObjectMapper mapper = new ObjectMapper();
String jsonState = mapper.writeValueAsString(state);

// Save to file
Files.writeString(Path.of("agent-state.json"), jsonState);

// Load state manually
String jsonState = Files.readString(Path.of("agent-state.json"));
Map<String, Object> state = mapper.readValue(jsonState, Map.class);

// Restore agent
agent.loadState(state);
```

### When to Use Low-Level API

Use the low-level API when you need:
- Custom serialization formats (not JSON)
- Integration with existing storage systems
- Partial state updates
- State transformation during load/save

## Complete Example

```java
package io.agentscope.tutorial.task;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.SessionManager;

import java.nio.file.Path;
import java.util.List;

public class SessionExample {
   public static void main(String[] args) {
      // Session ID (e.g., user ID, conversation ID)
      String sessionId = "user-alice-chat-001";
      Path sessionPath = Path.of("./sessions");

      // Create model
      DashScopeChatModel model = DashScopeChatModel.builder()
              .apiKey(System.getenv("DASHSCOPE_API_KEY"))
              .modelName("qwen3-max")
              .build();

      // Create agent
      ReActAgent agent = ReActAgent.builder()
              .name("Assistant")
              .sysPrompt("You are a helpful assistant. Remember previous conversations.")
              .model(model)
              .memory(new InMemoryMemory())
              .build();

      // Try to load existing session
      SessionManager.forSessionId(sessionId)
              .withSession(new JsonSession(sessionPath))
              .addComponent(agent)
              .loadIfExists();

      // Interact with agent
      Msg userMsg = Msg.builder()
              .name("user")
              .role(MsgRole.USER)
              .content(List.of(TextBlock.builder()
                      .text("Hello! My name is Alice.")
                      .build()))
              .build();

      Msg response = agent.call(userMsg).block();
      System.out.println("Agent: " + response.getTextContent());

      // Save session
      SessionManager.forSessionId(sessionId)
              .withSession(new JsonSession(sessionPath))
              .addComponent(agent)
              .saveSession();

      System.out.println("Session saved to: " + sessionPath.resolve(sessionId + ".json"));
   }
}
```

## Best Practices

1. **Use Meaningful Session IDs**: Use user IDs, conversation IDs, or other identifiers
   ```java
   // Good
   SessionManager.forSessionId("user-123-conversation-456")

   // Avoid
   SessionManager.forSessionId("session1")
   ```

2. **Save Regularly**: Persist state after important operations
   ```java
   agent.call(criticalMsg).block();
   sessionManager.saveSession();  // Save immediately
   ```

3. **Use `loadIfExists()` for Graceful Startup**: Don't fail if session doesn't exist
   ```java
   // Graceful - creates new session if not found
   SessionManager.forSessionId(sessionId)
           .withJsonSession(path)
           .addComponent(agent)
           .loadIfExists();
   ```

4. **Clean Up Old Sessions**: Delete sessions that are no longer needed
   ```java
   if (userLoggedOut) {
       SessionManager.forSessionId(sessionId)
               .withJsonSession(path)
               .deleteIfExists();
   }
   ```

5. **Handle Errors**: Use try-catch for critical operations
   ```java
   try {
       SessionManager.forSessionId(sessionId)
               .withJsonSession(path)
               .addComponent(agent)
               .saveOrThrow();
   } catch (Exception e) {
       logger.error("Failed to save session", e);
       // Handle error (retry, notify user, etc.)
   }
   ```

## Troubleshooting

### Session Not Loading

**Problem**: `loadIfExists()` doesn't restore state

**Solutions**:
- Verify session file exists: `sessionManager.sessionExists()`
- Check component names match between save and load
- Ensure same component types are used

### State Corruption

**Problem**: Session loads but agent behaves unexpectedly

**Solutions**:
- Validate session file format (should be valid JSON)
- Check for version mismatches between saved and current code
- Delete and recreate session if corrupted

### File Permission Errors

**Problem**: Cannot write to session directory

**Solutions**:
- Check directory permissions
- Ensure parent directory exists
- Use absolute paths instead of relative paths

## Next Steps

- [Memory](memory.md) - Understand memory management
- [Agent](../quickstart/agent.md) - Build stateful agents
- [Key Concepts](../quickstart/key-concepts.md) - Learn about Session concept

## Reference

- [SessionManager.java](https://github.com/agentscope-ai/agentscope-java/blob/main/src/main/java/io/agentscope/core/session/SessionManager.java)
- [JsonSession.java](https://github.com/agentscope-ai/agentscope-java/blob/main/src/main/java/io/agentscope/core/session/JsonSession.java)
- [StateModule.java](https://github.com/agentscope-ai/agentscope-java/blob/main/src/main/java/io/agentscope/core/state/StateModule.java)
