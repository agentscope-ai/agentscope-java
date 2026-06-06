# Agent State Store

`io.agentscope.core.state.AgentStateStore` is the AgentScope interface used to persist agent state — things like Memory, Workspace, and Plan are serialized to `State` objects and persisted through an `AgentStateStore` so that the agent can resume after restart and share state across nodes.

State is addressed by a `(userId, sessionId)` pair rather than a wrapper key object:

- `sessionId` — non-null, non-blank; identifies a conversation / session.
- `userId` — nullable. `null` represents an anonymous / single-tenant caller (CLI usage, tests). Implementations group all anonymous sessions under one namespace.

The `agentscope-extensions-*` repository ships two production-ready implementations:

| Extension | Backend | Best for |
| --- | --- | --- |
| [MySQL State Store](mysql.md) | MySQL or compatible | Existing database-backed apps; need transactions / audit / SQL queries |
| [Redis State Store](redis.md) | Jedis / Lettuce / Redisson | High concurrency, low latency, multi-node shared state |

Both implement the same `AgentStateStore` interface and plug into the agent at build time:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)   // any AgentStateStore implementation
    .build();
```

The agent instance itself is stateless with respect to sessions. Which `(userId, sessionId)` slot a call reads / writes is decided per-call via the `RuntimeContext`:

```java
RuntimeContext rc = RuntimeContext.builder()
    .userId("alice")          // optional; null = anonymous
    .sessionId("session-1")   // required
    .build();

agent.call(msg, rc).block();  // loads/saves state for (alice, session-1)
```

## Common features

- **Mixed single + list storage**: `save(userId, sessionId, key, State)` for single values, `save(userId, sessionId, key, List<State>)` for lists.
- **Incremental list writes**: lists use a hash digest + length comparison so append-only growth becomes a pure append; full rewrite happens only when the list is mutated or shrinks.
- **JSON serialization**: `JsonUtils.getJsonCodec()` is used uniformly for `State` ↔ JSON conversion — readable across language and version boundaries.

## Choosing one

| Scenario | Recommendation |
| --- | --- |
| Single node or want minimal infrastructure | MySQL (or even H2/SQLite as drop-in) |
| Existing Redis cluster, latency-sensitive | Redis |
| Need SQL reports / audit trail / transactional consistency | MySQL |
| Same session must be shared across services | Redis (Redisson supports distributed locks) |
