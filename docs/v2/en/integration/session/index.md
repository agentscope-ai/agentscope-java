# Session

`io.agentscope.core.session.Session` is the AgentScope interface used to persist "session state" — things like Memory, Workspace, and Plan are serialized to `State` objects and persisted through a `Session` so that the agent can resume after restart and share state across nodes.

The `agentscope-extensions-*` repository ships two production-ready implementations:

| Extension | Backend | Best for |
| --- | --- | --- |
| [MySQL Session](mysql.md) | MySQL or compatible | Existing database-backed apps; need transactions / audit / SQL queries |
| [Redis Session](redis.md) | Jedis / Lettuce / Redisson | High concurrency, low latency, multi-node shared state |

Both implement the same `Session` interface and plug into `SessionManager`:

```java
SessionManager.builder()
    .session(session)   // any of the two implementations
    .build();
```

## Common features

- **Mixed single + list storage**: `save(sessionKey, key, State)` for single values, `save(sessionKey, key, List<State>)` for lists.
- **Incremental list writes**: lists use a hash digest + length comparison so append-only growth becomes a pure append; full rewrite happens only when the list is mutated or shrinks.
- **JSON serialization**: `JsonUtils.getJsonCodec()` is used uniformly for `State` ↔ JSON conversion — readable across language and version boundaries.

## Choosing one

| Scenario | Recommendation |
| --- | --- |
| Single node or want minimal infrastructure | MySQL (or even H2/SQLite as drop-in) |
| Existing Redis cluster, latency-sensitive | Redis |
| Need SQL reports / audit trail / transactional consistency | MySQL |
| Same session must be shared across services | Redis (Redisson supports distributed locks) |
