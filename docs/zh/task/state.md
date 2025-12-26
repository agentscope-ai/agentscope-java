# State（状态管理）

State 提供组件状态的序列化和反序列化能力，是 Session 持久化的底层基础。

---

## 核心接口

所有支持状态管理的组件都实现 `StateModule` 接口：

```java
public interface StateModule {
    Map<String, Object> stateDict();                           // 导出状态
    void loadStateDict(Map<String, Object> state, boolean strict);  // 导入状态
    String getComponentName();                                 // 组件名称
}
```

**内置支持**：`ReActAgent`、`InMemoryMemory`、`Toolkit`、`PlanNotebook` 等均已实现此接口。

---

## 使用方式

### 推荐：使用 Session API

大多数场景建议使用 [Session](./session.md) 高级 API，自动处理序列化和存储：

```java
// 保存
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .saveSession();

// 加载
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .loadIfExists();
```

### 高级：手动状态管理

需要自定义序列化或与现有存储集成时，可直接使用底层 API：

```java
// 导出状态
Map<String, Object> state = agent.stateDict();

// 序列化（可用任意格式）
ObjectMapper mapper = new ObjectMapper();
String json = mapper.writeValueAsString(state);

// 反序列化并恢复
Map<String, Object> loaded = mapper.readValue(json, Map.class);
agent.loadStateDict(loaded, false);
```

---

## 自定义组件

实现 `StateModule` 接口使自定义组件支持持久化：

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

## 相关文档

- [Session](./session.md) - 高级会话管理 API
- [Memory](./memory.md) - 记忆管理
