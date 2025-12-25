# State

State provides serialization and deserialization capabilities for component state, serving as the foundation for Session persistence.

---

## Core Interface

All state-aware components implement the `StateModule` interface:

```java
public interface StateModule {
    Map<String, Object> stateDict();                           // Export state
    void loadStateDict(Map<String, Object> state, boolean strict);  // Import state
    String getComponentName();                                 // Component name
}
```

**Built-in support**: `ReActAgent`, `InMemoryMemory`, `Toolkit`, `PlanNotebook`, etc. all implement this interface.

---

## Usage

### Recommended: Use Session API

For most scenarios, use the high-level [Session](./session.md) API which handles serialization and storage automatically:

```java
// Save
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .saveSession();

// Load
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .loadIfExists();
```

### Advanced: Manual State Management

For custom serialization or integration with existing storage systems, use the low-level API directly:

```java
// Export state
Map<String, Object> state = agent.stateDict();

// Serialize (any format)
ObjectMapper mapper = new ObjectMapper();
String json = mapper.writeValueAsString(state);

// Deserialize and restore
Map<String, Object> loaded = mapper.readValue(json, Map.class);
agent.loadStateDict(loaded, false);
```

---

## Custom Components

Implement `StateModule` to enable persistence for custom components:

```java
public class MyComponent implements StateModule {
    private String data;

    @Override
    public Map<String, Object> stateDict() {
        return Map.of("data", data);
    }

    @Override
    public void loadStateDict(Map<String, Object> state, boolean strict) {
        this.data = (String) state.get("data");
    }

    @Override
    public String getComponentName() {
        return "myComponent";
    }
}
```

---

## Related Documentation

- [Session](./session.md) - High-level session management API
- [Memory](./memory.md) - Memory management
