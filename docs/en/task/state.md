# State

State provides serialization and deserialization capabilities for component state, serving as the foundation for Session persistence.

---

## Core Interfaces

### StateModule Interface

All state-aware components implement the `StateModule` interface:

```java
public interface StateModule {
    // Save state to Session
    void saveTo(Session session, SessionKey sessionKey);

    // Load state from Session
    void loadFrom(Session session, SessionKey sessionKey);

    // Load state from Session (if exists), returns whether loading succeeded
    default boolean loadIfExists(Session session, SessionKey sessionKey) {
        if (session.exists(sessionKey)) {
            loadFrom(session, sessionKey);
            return true;
        }
        return false;
    }
}
```

**Built-in support**: `ReActAgent`, `InMemoryMemory`, `PlanNotebook`, etc. all implement this interface.

### State Interface

`State` is a marker interface that identifies objects that can be stored by Session:

```java
public interface State {
    // Marker interface, no methods to implement
}
```

---

## Usage

### Recommended: Use Agent's saveTo/loadFrom

For most scenarios, call the state management methods directly on Agent:

```java
import io.agentscope.core.session.JsonSession;

// Create Session
Session session = new JsonSession(Path.of("sessions"));

// Save
agent.saveTo(session, "user123");

// Load (silently skip if doesn't exist)
agent.loadIfExists(session, "user123");

// Load (throw exception if doesn't exist)
agent.loadFrom(session, "user123");
```

### Direct Session API Usage

Session provides type-safe state storage API:

```java
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;

// Define state class
public record UserPreferences(String theme, String language) implements State {}

// Save single state
session.save(sessionKey, "preferences", new UserPreferences("dark", "en"));

// Get single state
Optional<UserPreferences> prefs = session.get(sessionKey, "preferences", UserPreferences.class);

// Save state list
session.save(sessionKey, "history", List.of(msg1State, msg2State));

// Get state list
List<MsgState> history = session.getList(sessionKey, "history", MsgState.class);
```

---

## Custom Components

Implement `StateModule` to enable persistence for custom components:

```java
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.State;
import io.agentscope.core.state.StateModule;

public class MyComponent implements StateModule {
    private String data;
    private int counter;

    // Define component's state class
    public record MyState(String data, int counter) implements State {}

    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        session.save(sessionKey, "myComponent", new MyState(data, counter));
    }

    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        session.get(sessionKey, "myComponent", MyState.class)
            .ifPresent(state -> {
                this.data = state.data();
                this.counter = state.counter();
            });
    }
}
```

---

## SessionKey

`SessionKey` identifies a session. `SimpleSessionKey` is the common implementation:

```java
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.SessionKey;

// Create SessionKey
SessionKey key = SimpleSessionKey.of("user123");

// Get session ID
String sessionId = ((SimpleSessionKey) key).sessionId();
```

---

## Related Documentation

- [Session](./session.md) - Session management API
- [Memory](./memory.md) - Memory management
