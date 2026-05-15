---
hide-toc: true
---

# AgentScope Java 1.1 · Harness

AgentScope Java 1.1 introduces the first public release of the **Harness Framework**: a production runtime layer for long-running, distributed agents.

Harness is designed to preserve the practical "continuous evolution" experience seen in products like OpenClaw/Hermes, while adding the safety and operational boundaries required in enterprise environments.

---

## Why Harness

Many agent stacks work well for personal assistants, but hit real limits in production:

1. **Distributed workspace state**: local directory assumptions break in multi-replica deployments.
2. **Execution safety**: user-driven shell/code execution must be isolated from host processes.
3. **Storage abstraction**: agent logic should not be coupled to one storage backend.
4. **Subagent orchestration**: task delegation and lifecycle management become complex quickly.
5. **Memory governance**: context growth and cross-session fact retention need first-class support.

Harness addresses these with one integrated runtime model.

---

## Core Design

### Workspace as source of truth

Harness organizes long-lived agent state under a structured workspace:

- persona and rules (`AGENTS.md`)
- long-term memory (`MEMORY.md`)
- domain knowledge
- skills
- subagent definitions
- session artifacts

Before each call, key workspace artifacts are injected into context.  
After each call, new facts and state are persisted back.

### `AbstractFilesystem` as portability layer

Harness decouples logical file operations (`read`, `write`, `ls`, `grep`) from physical storage/execution:

- local disk
- remote storage
- sandbox filesystem
- composite routing across backends

This lets teams move from local prototypes to distributed production without rewriting business-agent logic.

---

## What 1.1 Delivers

- **Workspace-driven runtime** for durable identity and evolving behavior
- **Pluggable filesystem abstraction** for local/remote/sandbox execution
- **Built-in context governance** (compaction + layered memory)
- **Subagent orchestration** with sync/async delegation and task lifecycle

---

## Typical Deployment Scenarios

### Personal productivity agents

- local execution
- durable memory and persona
- workspace-driven skill iteration

### Enterprise data/service agents

- sandboxed execution for untrusted input
- distributed memory/session continuity across replicas
- async subagent orchestration for long-running tasks

### API-first online agents

- strict tool boundaries (no implicit shell exposure)
- shared remote state for cross-instance continuity
- stable multi-turn behavior under production traffic

---

## Quick Start (Harness)

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-harness</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("my-agent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))
    .compaction(CompactionConfig.builder()
        .triggerMessages(50)
        .keepMessages(20)
        .build())
    .build();

RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("user-session-001")
    .userId("alice")
    .build();

Msg reply = agent.call(userMessage, ctx).block();
```

---

## Recommended Reading

- [Harness Overview](../harness/overview.md)
- [Harness Architecture](../harness/architecture.md)
- [Filesystem](../harness/filesystem.md)
- [Sandbox](../harness/sandbox/index.md)
- [Chinese full article](../../zh/blogs/agentscope-v1-harness.md)
