# State（状态管理）

State 提供组件状态的序列化和反序列化能力，是 Session 持久化的底层基础。

---

## 核心接口

### StateModule 接口

所有支持状态管理的组件都实现 `StateModule` 接口：

```java
public interface StateModule {
    // 保存状态到 Session
    void saveTo(Session session, SessionKey sessionKey);

    // 从 Session 加载状态
    void loadFrom(Session session, SessionKey sessionKey);

    // 从 Session 加载状态（如存在），返回是否成功加载
    default boolean loadIfExists(Session session, SessionKey sessionKey) {
        if (session.exists(sessionKey)) {
            loadFrom(session, sessionKey);
            return true;
        }
        return false;
    }
}
```

**内置支持**：`ReActAgent`、`InMemoryMemory`、`PlanNotebook` 等均已实现此接口。

### State 接口

`State` 是一个标记接口，用于标识可以被 Session 存储的状态对象：

```java
public interface State {
    // 标记接口，无需实现任何方法
}
```

---

## 使用方式

### 推荐：使用 Agent 的 saveTo/loadFrom

大多数场景建议直接调用 Agent 的状态管理方法：

```java
import io.agentscope.core.session.JsonSession;

// 创建 Session
Session session = new JsonSession(Path.of("sessions"));

// 保存
agent.saveTo(session, "user123");

// 加载（会话不存在时静默跳过）
agent.loadIfExists(session, "user123");

// 加载（会话不存在时抛异常）
agent.loadFrom(session, "user123");
```

### 直接使用 Session API

Session 提供类型安全的状态存储 API：

```java
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;

// 定义状态类
public record UserPreferences(String theme, String language) implements State {}

// 保存单个状态
session.save(sessionKey, "preferences", new UserPreferences("dark", "zh"));

// 获取单个状态
Optional<UserPreferences> prefs = session.get(sessionKey, "preferences", UserPreferences.class);

// 保存状态列表
session.save(sessionKey, "history", List.of(msg1State, msg2State));

// 获取状态列表
List<MsgState> history = session.getList(sessionKey, "history", MsgState.class);
```

---

## 自定义组件

实现 `StateModule` 接口使自定义组件支持持久化：

```java
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.State;
import io.agentscope.core.state.StateModule;

public class MyComponent implements StateModule {
    private String data;
    private int counter;

    // 定义组件的状态类
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

`SessionKey` 用于标识会话，`SimpleSessionKey` 是常用的实现：

```java
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.SessionKey;

// 创建 SessionKey
SessionKey key = SimpleSessionKey.of("user123");

// 获取 session ID
String sessionId = ((SimpleSessionKey) key).sessionId();
```

---

## 相关文档

- [Session](./session.md) - 会话管理 API
- [Memory](./memory.md) - 记忆管理
