# Harness Overview

`agentscope-harness` builds a production-grade runtime infrastructure on top of `agentscope-core`'s `ReActAgent`, through two extension channels: **Hooks** and **Toolkits**. Your entry point is one class: `HarnessAgent`.

A bare `ReActAgent` handles a single request–reason–tool–reply cycle. Harness answers a different set of questions: **what happens on the next turn, what happens the next day, what happens when context overflows, what happens when state is lost, what happens when a task is too heavy**. It does not replace the reasoning loop — it injects hooks at critical lifecycle points and provides a baseline set of tools, packaging the default engineering answers to these questions.

## Quick Start

Add the dependency:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

The example below demonstrates three core values of Harness: **workspace-driven persona**, **session persistence** (a second turn with the same `sessionId` remembers the first), and **explicit conversation compaction**. On first run, `AGENTS.md` is generated under `${cwd}/.agentscope/workspace/`; subsequent runs reuse it.

```java
public class QuickstartExample {

    public static void main(String[] args) throws Exception {
        // 1. Prepare workspace: generate AGENTS.md on first run, reuse afterwards
        Path workspace = Paths.get(".agentscope/workspace");
        initWorkspaceIfAbsent(workspace);

        // 2. Build model
        Model model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-max")
                .stream(true)
                .build();

        // 3. Build HarnessAgent: workspace injection, session persistence, and trace logging
        //    are enabled by default; compaction is explicitly configured here
        HarnessAgent agent = HarnessAgent.builder()
                .name("quickstart-agent")
                .sysPrompt("You are a note-taking assistant.")
                .model(model)
                .workspace(workspace)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .flushBeforeCompact(true)   // extract facts to daily log before compacting
                        .build())
                .build();

        // 4. Two conversation turns with the same RuntimeContext
        //    Same sessionId → turn 2 auto-restores state from turn 1
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("demo-session")
                .userId("alice")
                .build();

        Msg turn1 = agent.call(
                Msg.builder().role(MsgRole.USER)
                        .textContent("My name is Alice, and I'm preparing a tech talk on ReAct today.")
                        .build(),
                ctx).block();
        System.out.println("[turn1] " + turn1.getTextContent());

        Msg turn2 = agent.call(
                Msg.builder().role(MsgRole.USER)
                        .textContent("What is my name? What am I doing today?")
                        .build(),
                ctx).block();
        System.out.println("[turn2] " + turn2.getTextContent());
    }

    private static void initWorkspaceIfAbsent(Path workspace) throws Exception {
        Files.createDirectories(workspace);
        Path agentsMd = workspace.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) return;
        Files.writeString(agentsMd, """
                # Note-taking Assistant

                You are an assistant that helps users organize notes and knowledge.

                ## Behavior Guidelines
                - Actively record key facts the user mentions (names, plans, preferences, etc.)
                - Reply concisely, using bullet lists when helpful
                - For uncertain information, say so clearly rather than guessing
                """);
    }
}
```

Full runnable version: [`agentscope-examples/agents/harness-examples/harness-quickstart/src/main/java/io/agentscope/harness/example/QuickstartExample.java`](../../../agentscope-examples/agents/harness-examples/harness-quickstart/src/main/java/io/agentscope/harness/example/QuickstartExample.java)

Run:

```bash
export DASHSCOPE_API_KEY=your_key_here

# First run: install dependency modules to local repo (skip javadoc and spotless)
mvn -pl agentscope-examples/agents/harness-examples/harness-quickstart -am install \
    -DskipTests -Dspotless.check.skip=true -Dmaven.javadoc.skip=true -q

# Execute main
mvn -pl agentscope-examples/agents/harness-examples/harness-quickstart exec:java \
    -Dexec.mainClass=io.agentscope.harness.example.QuickstartExample \
    -Dspotless.check.skip=true -q
```

**What to observe after running**:

- `.agentscope/workspace/AGENTS.md` is auto-created — this is the source of the agent's persona
- Turn 2 "What is my name?" gets the right answer because the second `call()` with the same `sessionId=demo-session` auto-restored turn 1's state via `bindRuntimeContext` at the start
- After a few more turns that trigger compaction (≥30 messages), you can see LLM-extracted facts in `workspace/memory/YYYY-MM-DD.md`; the background `MemoryMaintenanceScheduler` will continue merging these into `MEMORY.md`
- On the next process restart, as long as `sessionId` is unchanged, the agent still remembers everything

**About `RuntimeContext`**: it is the identity carrier for the current `call()`. `sessionId` determines the storage path and log archive location; `userId` determines the default filesystem namespace (natural multi-tenant isolation). It is **not persisted** — it is only shared between hooks and tools within the current call.

**Extension directions**: place `KNOWLEDGE.md`, `skills/*/SKILL.md`, or `subagents/*.md` in the workspace to enable domain knowledge injection, skill loading, and subagent orchestration respectively. Add `.toolResultEviction(ToolResultEvictionConfig.defaults())` to enable large-result offloading. Use [Filesystem — Three Declarative Modes](./filesystem.md) to choose between **shared storage, sandbox, or local+shell** for where files and commands land. For isolated execution prefer `filesystem(SandboxFilesystemSpec)` (see [Sandbox](./sandbox/index.md)); `abstractFilesystem` is only an escape hatch for self-managed backends.

## Core Capabilities

Each capability answers **one problem → one component**:

- **Workspace context injection** — answers *where does the agent's identity come from*. Before every reasoning turn, `WorkspaceContextHook` injects `AGENTS.md`, `MEMORY.md`, today's memory, and `KNOWLEDGE.md` into the system prompt. The workspace is the agent's "persona and knowledge base".
- **Two-layer persistent memory** — answers *how do conversation facts persist across sessions*. `MemoryFlushHook` uses an LLM to distill conversation into a daily fact log before compaction; `MemoryConsolidator` runs in the background to merge and deduplicate daily logs into the long-term `MEMORY.md`. Still available on next startup.
- **Compaction and overflow recovery** — answers *what to do when history gets too long*. `CompactionHook` summarizes history and keeps a recent tail when message/token thresholds are exceeded; when the model actually reports a context overflow, `HarnessAgent` catches the error, forces compaction, and retries automatically.
- **Large tool result offloading** — answers *what to do when a single tool call returns too much*. `ToolResultEvictionHook` writes oversized results to the filesystem and keeps only a head+tail preview with a placeholder in context; the agent can re-read on demand.
- **Session persistence** — answers *how to preserve state across processes*. `SessionPersistenceHook` writes agent state to the workspace by `sessionId`; the next call automatically resumes from where it left off.
- **Subagent orchestration** — answers *how to decompose complex tasks*. `SubagentsHook` injects `task` / `task_output` tools; the parent agent can delegate synchronously or in the background. Subagents can be declared via workspace spec files, programmatic specs, or custom factories.
- **Pluggable filesystem** — answers *how to isolate and control the agent's environment*. All file tools go through `AbstractFilesystem`. Choose from [three declarative modes](./filesystem.md) (local+shell, composite+store, sandbox) or `abstractFilesystem` for self-managed backends. Multi-tenant / session-level isolation is handled via `RuntimeContext.userId` and `IsolationScope`.

Additionally, several infrastructure components support the above: `RuntimeContext` threads through the entire call, `MemoryMaintenanceScheduler` runs background merges and index maintenance, `AgentTraceHook` provides unified trace logging, and `AgentSkillRepository` auto-wires `SkillBox`.

## How Capabilities Work Together

These capabilities collectively support three pillars of "stable continuous operation":

- **Identity continuity** — *workspace context injection* re-feeds persona and knowledge to the model each turn; *two-layer persistent memory* distills valuable facts from conversation back into the workspace; *skill auto-loading* keeps reusable capabilities tied to the workspace. The agent's persona and knowledge do not disappear when a single call ends — they accumulate in the workspace over time.
- **Bounded context** — *conversation compaction* controls depth, *tool result offloading* controls width, and *overflow recovery* is the final safety net. Together they ensure that even in arbitrarily long sessions, the context never overwhelms the model — and if it does, recovery is seamless.
- **Recoverable state** — *session persistence* ensures a process restart can continue from where it left off; *RuntimeContext* threads the call's identity (`sessionId`/`userId`) through all hooks and tools; *pluggable filesystem* makes "where state actually lives" (local disk, sandbox, remote) a configuration choice.

The three pillars are connected by three shared objects: `WorkspaceManager` (who reads/writes the workspace), `AbstractFilesystem` (where the workspace lives physically), and `RuntimeContext` (who is speaking in this call). Each hook does only its own job and collaborates with others through these three objects — this is how Harness assembles independent capabilities into one "stably running agent".

## How Capabilities Are Injected

`HarnessAgent` is a thin wrapper around `Agent` + `StateModule`, internally holding a `ReActAgent delegate`. All capability injection happens in `HarnessAgent.Builder.build()`:

- **Hook channel**: hooks are assembled in `priority` order and passed to `ReActAgent` (including `SandboxLifecycleHook` in sandbox mode, see [Architecture](./architecture.md))
- **Toolkit channel**: `filesystem`, `memory_search`, `memory_get`, `session_search` are appended to the user's `Toolkit`; sandbox backends additionally add `shell_execute`; `SubagentsHook` itself registers `task` / `task_output`
- **SkillBox channel**: `SkillBox` is auto-constructed from `workspace/skills/` or a custom `AgentSkillRepository`

At the start of each `call()`, `bindRuntimeContext` distributes the current `RuntimeContext` to all hooks implementing `RuntimeContextAwareHook`, and restores state from `Session` as needed.

> Detailed behavior, trigger timing, and sequence diagrams for each component are in [Architecture](./architecture.md).

## Related Pages

- [Architecture](./architecture.md) — component definitions, lifecycle sequence diagrams, collaboration relationships
- [Workspace](./workspace.md) — workspace directory structure and context injection
- [Memory](./memory.md) — two-layer memory, compaction configuration, and full-text search
- [Filesystem](./filesystem.md) — three declarative modes and the `AbstractFilesystem` hierarchy
- [Sandbox](./sandbox/index.md) — isolated execution, sandbox state, and distributed options
- [Subagent](./subagent.md) — subagent specs and orchestration
- [Subagent Streaming](./streaming.md) — `stream()` mode child-agent event forwarding, `EventSource` fields, and multi-level nesting
- [Tooling](./tool.md) — built-in tool reference
- [Session](./session.md) — session persistence and state recovery
