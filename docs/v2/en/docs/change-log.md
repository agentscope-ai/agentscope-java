---
title: "Changelog"
description: "Core differences between AgentScope Java 2.0 and 1.0"
---

:::{note}
**Current latest version: `2.0.0-RC1`**. See the [GitHub release notes](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.0-RC1) for the full release-by-release change list.
:::

AgentScope Java 2.0 aims to preserve compatibility with 1.x where possible so that most users can upgrade smoothly. That said, 2.0 does introduce API-level changes. This page splits those changes into two sections:

- **Migration Guide** — what changes against 1.x, in two tiers:
  - **Part A · Required** — your code will fail to compile or throw at runtime if you don't migrate
  - **Part B · Recommended** — still works but `@Deprecated(forRemoval = true)`; will be removed in the next minor
- **What's New** — net-new capabilities that don't appear in the Migration Guide

## Migration Guide

### Part A — Required (compile errors or runtime exceptions if you don't migrate)

Items in this section are removed, renamed, or have their semantics tightened. Code that worked on 1.x will not work as-is on 2.0.

#### A.1 Removed `ReActAgent.Builder` methods

| Removed in 2.0 | Replacement |
|---|---|
| `.memory(Memory)` | `.session(Session).sessionKey(SessionKey)` — `AgentState.getContext()` holds the conversation; the configured `Session` saves/loads automatically on every `call()` |
| `.statePersistence(StatePersistence)` | Same — `Session` subsumes persistence |

Detail → [Context](harness/context.md)

#### A.2 Removed packages and classes

| Removed in 2.0 | Replacement |
|---|---|
| `io.agentscope.core.session.SessionManager` | Configure `Session` + `SessionKey` on the agent builder; persistence happens automatically |
| `io.agentscope.core.pipeline.*` — `Pipeline`, `Pipelines`, `SequentialPipeline`, `FanoutPipeline`, `MsgHub` | Compose middleware + sub-agents + the event stream for multi-agent orchestration. See the subagent guide → [Subagent](harness/subagent.md) |
| `io.agentscope.core.model.tts.*` (14 files, DashScope TTS / Realtime TTS / `AudioPlayer`, etc.) | Core no longer ships TTS. Integrate the upstream provider SDK directly if you need TTS |
| `io.agentscope.core.hook.PendingToolRecoveryHook` | Use `Builder.enablePendingToolRecovery(boolean)` |
| `io.agentscope.core.hook.TTSHook` | Removed alongside the TTS module |

#### A.3 `state` package restructure (compile error)

| v1 | v2 |
|---|---|
| `AgentMetaState` | `AgentState` |
| `StateModule` | **removed** — no longer a superclass for `Memory`, `Toolkit`, etc. |
| `StatePersistence` | **removed** — replaced by the `Session` abstraction |
| `ToolkitState` | Moved to `io.agentscope.core.session.legacy.ToolkitState` (kept for compatibility only — do not reference in new code) |
| (new) | `Task`, `TaskContextState`, `ToolContextState`, `PlanModeContextState`, `ReadCacheEntry` |

Any code that imports `AgentMetaState`, `StateModule`, `StatePersistence`, or `ToolkitState` from `io.agentscope.core.state` will fail to compile. Detail → [Context](harness/context.md)

#### A.4 `Msg` content validation is stricter (runtime exception)

`Msg` now validates `content` against `role` at construction time:

- `USER` — only `TextBlock` / `DataBlock` / `ImageBlock` / `AudioBlock` / `VideoBlock`
- `SYSTEM` — only `TextBlock`
- `ASSISTANT` — unrestricted

Combinations that v1 tolerated (for example, a `USER` message carrying a `ToolUseBlock`) now throw at construction. Use the role-pinned subclasses `UserMessage` / `AssistantMessage` / `SystemMessage` / `ToolResultMessage` to make role/content compatibility obvious at the call site. Detail → [Message & Event](building-blocks/message-and-event.md)

---

### Part B — Recommended (`@Deprecated(forRemoval = true)`, still callable today)

Items in this section compile and run on 2.0, but each has been marked for removal in the next minor. Migrate at your own pace; we recommend doing it sooner rather than later.

#### B.1 `SkillBox` → skill repositories

- `SkillBox` (the class) and `Builder.skillBox(SkillBox)` are both `@Deprecated(forRemoval = true, since = "2.0.0")`.
- Recommended path: register one or more `AgentSkillRepository` implementations (built-ins: `ClasspathSkillRepository`, `FileSystemSkillRepository`) via `Builder.skillRepository(...)` / `.skillRepositories(...)`. When at least one repository is registered, `DynamicSkillMiddleware` is auto-installed and rebuilds the skill prompt on every `call()`.
- Fine-grained filtering: `Builder.skillFilter(SkillFilter)`. To disable the auto-installed middleware (so an external orchestrator like `HarnessAgent` can attach its own), use `Builder.dynamicSkillsEnabled(false)`.

Detail → [Skill](harness/skill.md)

#### B.2 Hook → Middleware

The entire `io.agentscope.core.hook` package — the `Hook` interface, `HookEvent`, `HookEventType`, and all `*Event` classes — is `@Deprecated(forRemoval = true, since = "2.0.0")`. Existing imports still compile, and `Builder.hook(...)` / `.hooks(...)` are kept callable via `LegacyHookDispatcher` so v1 code does not break overnight. The recommended extension surface is now `io.agentscope.core.middleware`:

- `MiddlewareBase` exposes five stages: the onion-shaped `onAgent` / `onReasoning` / `onActing` / `onModelCall`, and the pipeline-shaped `onSystemPrompt`.
- Builder methods: `.middleware(MiddlewareBase)` and `.middlewares(List<? extends MiddlewareBase>)`.
- Built-in: `TaskReminderMiddleware` (pairs with `TodoTools`, re-injects the task list before each reasoning step).

Detail → [Middleware](building-blocks/middleware.md)

#### B.3 `Memory` → `Session` + `AgentState`

- The `io.agentscope.core.memory.Memory` interface and every implementation (`InMemoryMemory`, `LongTermMemory`, …) are `@Deprecated(forRemoval = true, since = "2.0.0")`.
- `Memory` no longer extends `StateModule`. It gains `saveTo(Session, SessionKey)` / `loadFrom(Session, SessionKey)` as a v1 bridge so existing implementations can still round-trip through `Session`.
- Recommended model:
  - **Conversation history** lives on `AgentState.getContext()`.
  - **Persistence** uses the `Session` abstraction (built-in: `InMemorySession`, `JsonSession`), partitioned by `SessionKey`.
  - Builder chain: `.session(Session).sessionKey(SessionKey)` — `AgentState` is saved/loaded automatically on every `call()`.

Detail → [Context](harness/context.md)

#### B.4 Event subscription: hooks + chunk events → `streamEvents()`

Code that watched text or tool-call deltas via `Hook` + `*ChunkEvent` in v1 can migrate to `agent.streamEvents()`, which returns a `Flux<AgentEvent>` covering 28 typed events across the full agent lifecycle and the HITL flow (`RequireUserConfirmEvent`, `RequireExternalExecutionEvent`, `UserConfirmResultEvent`, `ExternalExecutionResultEvent`, …).

Alongside the new event stream, the `Msg` refactor adds:

- `DataBlock` — unified multimodal block, accepts base64 or URL sources
- `HintBlock` — agent guidance / intermediate reasoning
- `ToolCallState` / `ToolResultState` on `ToolUseBlock` / `ToolResultBlock` — tool-call lifecycle
- `id` field on every block — stable references across the stream

Detail → [Message & Event](building-blocks/message-and-event.md)

##### `stream()` → `streamEvents()` (alignment with Python 2.0)

Python 2.0's `agent.reply_stream()` exposes a single streaming signature (`AsyncGenerator[AgentEvent, None]`) that maps directly to Java's fine-grained `io.agentscope.core.event.AgentEvent` hierarchy. To match it, the coarse-grained `Flux<Event> stream(...)` API on the Java side is `@Deprecated` as of 2.0.0:

- **Methods (`forRemoval = true`, going away next minor)**
  - `StreamableAgent.stream(...)` — all 11 `stream(...)` overloads on the interface (defaults + abstract)
  - `AgentBase.stream(...)` — 3 `Flux<Event>` implementations
  - `ReActAgent.stream(..., RuntimeContext)` — 4 `RuntimeContext`-suffixed overloads
  - `HarnessAgent.stream(...)` — 9 overloads (3 interface `@Override`s + 6 `RuntimeContext` variants). `HarnessAgent` gains 4 new `streamEvents(Msg/List<Msg>[, RuntimeContext])` methods that delegate to `ReActAgent.streamEvents(...)` while reusing the sandbox lifecycle `acquireForCall` / `releaseForCall`
  - `ReActAgent.streamEvents(..., RuntimeContext)` added — mirrors `call(..., RuntimeContext)` for context propagation
- **Types (soft deprecation, no `forRemoval` yet)**
  - `io.agentscope.core.agent.Event`, `EventType`, `EventSource`
  - Still consumed internally by the harness (subagent event forwarding: `SubAgentTool` / `SubagentEventBus` / `DefaultAgentManager` / `AgentSpawnTool`), AGUI, A2A, chat-completions-web, and Kotlin extension modules as the event-bus / adapter input. They will be flipped to `forRemoval = true` only after those modules migrate to `AgentEvent`, so the entire downstream is not warning-flooded in a single release.
  - **Current gap:** `HarnessAgent.streamEvents(...)` does **not** forward subagent events yet — the `AgentEvent` hierarchy has no equivalent `EventSource` channel. Callers that need the child-agent stream must stay on the deprecated `stream(...)` path until that channel lands.

New code should use:

```java
agent.streamEvents(new UserMessage("Hello"))
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                System.out.print(((TextBlockDeltaEvent) event).getDelta());
            }
        })
        .blockLast();
```

#### B.5 RAG module — in progress

- `Knowledge`, `KnowledgeRetrievalTools`, `RAGMode`, `GenericRAGHook` are all `@Deprecated(forRemoval = true, since = "2.0.0")`.
- The builder methods `.knowledge(...)` / `.knowledges(...)` / `.ragMode(...)` / `.retrieveConfig(...)` are deprecated in parallel.
- The v2 rewrite is underway. New knowledge base, document reader, and store APIs will land in subsequent minor releases. The v1 implementations remain callable in 2.0 for compatibility, but **new code should not depend on them**.

#### B.6 Long-term memory module — in progress

- `LongTermMemory`, `LongTermMemoryMode`, `LongTermMemoryTools` are all `@Deprecated(forRemoval = true, since = "2.0.0")`.
- The builder methods `.longTermMemory(...)` / `.longTermMemoryMode(...)` / `.longTermMemoryAsyncRecord(...)` are deprecated in parallel.
- Same status — being rewritten on the v2 architecture. New code should not depend on the current API.

#### B.7 Core shell / file tools — move to Harness

- `io.agentscope.core.tool.coding.*` (`ShellCommandTool`, `CommandValidator`, `UnixCommandValidator`, `WindowsCommandValidator`) and `io.agentscope.core.tool.file.*` (`ReadFileTool`, `WriteFileTool`, `FileToolUtils`) are all `@Deprecated(forRemoval = true, since = "2.0.0")`.
- These tools run commands and read/write files directly against the host process — no workspace or permission isolation — so they are being moved out of the core built-in toolset.
- Recommended path: use the `agentscope-harness` module to run equivalent tools inside a workspace context. You get unified local / Docker / cloud-sandbox backends, file-IO permissions, a read/write cache, and HITL approval for free.

Detail → [Harness filesystem](harness/filesystem.md)

---

## What's New

The capabilities below are additive in 2.0 — none of them break 1.x code. The Migration Guide above already covers the event system, message refactor, and middleware mechanism, so they are not repeated here.

### Toolkit & Permission

Tool execution is the main extension surface in 2.0, and the permission system sits directly on its execution path — so we present them together.

- **Toolkit upgrades**:
  - Unified base classes: `ToolBase` / `AgentTool`
  - Tool groups: `ToolGroup` / `ToolGroupScope` / `MetaToolFactory` — activate on demand; the reserved `basic` group is always on
  - Annotation-driven registration: `ReflectiveFunctionTool` + `@Tool` / `@ToolParam`; `Toolkit#registerTool(Object)` reflectively registers any annotated methods
  - Built-in task tool: `io.agentscope.core.tool.builtin.TodoTools.todoWrite` (pairs with `TaskReminderMiddleware`)
- **Permission system** (new package `io.agentscope.core.permission`):
  - `PermissionEngine`, `PermissionRule`, `PermissionMode` (`DEFAULT` / `ACCEPT_EDITS` / `EXPLORE` / `BYPASS` / `DONT_ASK`), `PermissionBehavior`
  - Every tool call goes through `PermissionEngine`: allow / require user confirmation / deny. HITL decisions flow back as `UserConfirmResultEvent`.

Detail → [Tool](building-blocks/tool.md), [Permission System](building-blocks/permission-system.md)

### Model fault tolerance and credentials

- New package `io.agentscope.core.credential` — 8 provider credential classes + `ModelCard`
- `ModelRegistry` resolves models from `"provider:model"` strings (e.g. `dashscope:qwen-max`, `openai:gpt-5`)
- Builder additions: `.model(String)`, `.maxRetries(int)`, `.fallbackModel(Model)` / `.fallbackModel(String)`, `.stopOnReject(boolean)` — primary-model failure auto-retries and falls back

Detail → [Model](building-blocks/model.md)

### Workspace (Harness module)

- Workspace abstraction unifies local filesystem, Docker, and E2B cloud sandbox execution behind a single interface
- Warm-up pool — pre-initialize execution environments in batches; useful for parallel RL rollouts

Detail → [Workspace](harness/workspace.md)

### Other new Builder methods

- `.enableTaskList(...)` / `.enableTaskList(boolean)` — enable the built-in `TodoTools`
- `.permissionContext(PermissionContextState)` — preload permission rules
- `ReActAgent.Builder.fromAgent(ReActAgent)` — derive a new builder from an existing agent's observable configuration (name, description, system prompt, model, maxIters, generateOptions, toolkit)
- `HarnessAgent.Builder.fromAgent(ReActAgent)` — ReActAgent → HarnessAgent migration helper. Inherits the same 7 fields as `ReActAgent.Builder.fromAgent` plus **every other observable configuration on ReActAgent**: `session` / `sessionKey`, `ModelConfig` (`maxRetries` / `fallbackModel`), `ReactConfig.stopOnReject`, `modelExecutionConfig` / `toolExecutionConfig` / `toolExecutionContext`, `structuredOutputReminder`, `enablePendingToolRecovery`, `checkRunning`, `permissionContext`, `middlewares`, and `hooks`. The only flags not copied are `enableMetaTool` / `enableTaskList` — these are builder-time toolkit-mutation flags, and the toolkit copy already carries the tools they registered. Harness-only config (workspace / filesystem / subagents / skills / plan mode / `disable*` toggles) still has to be set explicitly. See javadoc for the full table.
- **6 new getters on ReActAgent / parents to support the above migration**: `getModelExecutionConfig()` / `getToolExecutionConfig()` / `getToolExecutionContext()` / `isPendingToolRecoveryEnabled()` / `getPermissionContext()` (on `ReActAgent`); `getStructuredOutputReminder()` (on `StructuredOutputCapableAgent`); `isCheckRunning()` (on `AgentBase`).

Detail → [Agent](building-blocks/agent.md)
