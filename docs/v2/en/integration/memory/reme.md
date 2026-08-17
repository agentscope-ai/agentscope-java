# ReMe

`agentscope-extensions-reme` integrates with the self-hosted ReMe memory service. Its distinguishing features are **trajectory-based** memory extraction and **workspace-level** isolation.

## When to use

- You want a lightweight self-hosted memory service that's easy to spin up.
- You care about summarizing whole conversation trajectories rather than individual messages.
- You can express logical workspaces by `userId` (one workspace per user).

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
    .userId("task_workspace")            // Maps to ReMe's workspace_id
    .apiBaseUrl("http://localhost:8002") // Your ReMe server
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .longTermMemory(memory)
    .longTermMemoryMode(LongTermMemoryMode.BOTH)
    .build();
```

`userId` is mapped to ReMe's `workspace_id` — the smallest unit of memory partitioning in ReMe.

## How it works

- **Write (record)**: filtered messages are joined into a single `ReMeTrajectory` and posted to ReMe's `add` endpoint; the server then runs LLM extraction over the trajectory to produce searchable memory snippets.
- **Retrieve**: the current message is used as the query against ReMe's `search`. The server-aggregated `answer` field is returned when present, otherwise multiple memory snippets are joined.

Writes use the same filtering as Bailian:

- Only `USER` and `ASSISTANT` messages are kept.
- Assistant messages containing `ToolUseBlock` (tool-call requests) are skipped.
- Messages containing the `<compressed_history>` marker are skipped.

## Builder reference

| Method | Required | Default | Notes |
| --- | --- | --- | --- |
| `userId(String)` | ✅ | - | Workspace ID (used for both writes and reads) |
| `apiBaseUrl(String)` | ✅ | - | ReMe service URL, e.g. `http://localhost:8002` |
| `timeout(Duration)` | ❌ | `60s` | HTTP timeout |

> ReMe does not yet expose finer-grained metadata filtering. If you need tag-based segmentation, encode it inside `userId` (e.g. `tenant-a:project-1`).
