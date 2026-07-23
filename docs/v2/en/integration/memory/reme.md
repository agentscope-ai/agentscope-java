# ReMe

`agentscope-extensions-reme` integrates with the self-hosted ReMe memory service. In ReMe `0.4.x`, AgentScope-Java records filtered conversation messages through the `auto_memory` job and retrieves context through the `search` job.

## When to use

- You want a lightweight self-hosted memory service that's easy to run locally.
- You want ReMe to evolve memory from conversation sessions rather than from single isolated facts.
- You can manage memory scope with a ReMe `session_id` or with deployment-level isolation.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-reme</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.core.memory.reme.ReMeLongTermMemory;

ReMeLongTermMemory memory = ReMeLongTermMemory.builder()
    .sessionId("task-session")
    .apiBaseUrl("http://localhost:8002")
    .build();
```

`userId(String)` is still accepted for backward compatibility, but in ReMe `0.4.x` it is treated as the `session_id` fallback instead of `workspace_id`.

## How it works

- **Write (`record`)**: filtered `USER` and `ASSISTANT` messages are sent to `POST /auto_memory` with `messages` plus `session_id`.
- **Retrieve (`retrieve`)**: the current message text is sent to `POST /search`. If ReMe returns a non-empty `answer`, AgentScope-Java uses it directly; otherwise it joins `metadata.results[].text`.

Writes use the same filtering as Bailian:

- Only `USER` and `ASSISTANT` messages are kept.
- Assistant messages containing `ToolUseBlock` are skipped.
- Messages containing the `<compressed_history>` marker are skipped.

## Builder reference

| Method | Required | Default | Notes |
| --- | --- | --- | --- |
| `sessionId(String)` | Recommended | - | ReMe `session_id` used for write operations |
| `userId(String)` | Backward compatible alias | - | Treated as `session_id` when `sessionId` is absent |
| `apiBaseUrl(String)` | Yes | - | ReMe service URL, e.g. `http://localhost:8002` |
| `timeout(Duration)` | No | `60s` | HTTP timeout |

> ReMe `0.4.x` no longer exposes the old `workspace_id`-scoped personal-memory API. If you need strict per-user isolation, prefer one ReMe workspace or deployment per logical tenant/user, or encode that boundary into the chosen `session_id`.
