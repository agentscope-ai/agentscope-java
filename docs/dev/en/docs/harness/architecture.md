---
title: Harness Architecture
description: What HarnessAgent is, how to choose an invocation style, combine capabilities and persist conversation state
zh_link: /dev/zh/docs/harness/architecture
---

`HarnessAgent` combines reasoning, tools and context management with workspace, long-term memory, skills, subagents and sandboxes. Start with a working conversation in [Quickstart](/dev/en/docs/quickstart), then add capabilities for your application.

`ReActAgent` provides the reasoning loop, tools, permissions and conversation context APIs. Harness composes these capabilities with additional defaults and integrations. Both support `call` and `streamEvents`.

Configure a shared `HarnessAgent.Builder` at startup, build a fresh instance for each direct request, and close it after execution. Stable identity and the same log backend preserve the conversation. See [Instance lifecycle](/dev/en/docs/building-blocks/agent#instance-lifecycle) for code and the shared-instance option. A Durable adapter factory creates native Agents for managed invocations; Runtime owns persistent task scheduling.

## Core working principle

An execution loads the conversation state, builds model input, calls tools as needed and saves its results. Workspace, memory and compaction capabilities take effect during these steps. Applications customize behavior through the builder, tools and middleware.

## Choose an invocation style

- **Get a reply or invoke an Agent within a workflow**: use `call(input, ctx)`.
- **Display live text and tool progress within the current request**: use `streamEvents(input, ctx)`.
- **Run persistent background tasks with queueing, guidance and recovery**: use [DurableRuntime](/dev/en/docs/harness/durable).

Direct calls support ongoing conversations and save state through the default Thread journal and checkpoints. Durable handles persistent admission, Thread queues and interaction association. Harness supplies the same model, tools and middleware capabilities through the AgentScope adapter; it does not own another task scheduler.

Ordinary calls do not create persistent Turns. Local subagents have their own Runs and execution counters, with parent Run/Step references for provenance. They do not inherit parent control objects or recovery positions. Durable manages the corresponding Thread/Turn when persistent tasks, background takeover and cross-process control are required.

See [Agent](/dev/en/docs/building-blocks/agent) for direct calls and [Durable](/dev/en/docs/harness/durable) for background task workflows. Applications with an existing workflow scheduler can continue using direct calls.

## Core components

Configure capabilities and use session operations as your application needs them.

| Capability | What it solves | Configuration or operation | Detail |
|---|---|---|---|
| Workspace-driven persona | Persona, knowledge, subagent specs, skills, MCP allowlist all live as files | `.workspace(path)` | [Workspace](/dev/en/docs/harness/workspace) |
| State and execution history | Full facts, checkpoints, cross-request/node recovery | journal mode by default; override threadJournalStore | [Session logs](/dev/en/docs/harness/session-log) |
| Persistent tasks | Background work, queueing, guidance and recovery | `DurableRuntime` | [Durable](/dev/en/docs/harness/durable) |
| Two-layer long-term memory | Facts in long conversations sediment into `MEMORY.md` | opt-in via `.enableLongTermMemory()`; `.memory(...)` customizes prompts / trigger policy | [Memory](/dev/en/docs/harness/memory) |
| Conversation compaction | History bounded; force-retry on real overflow | `.compaction(...)` | [Context management](/dev/en/docs/harness/context) |
| Large tool-result offloading | >80K-char results moved to disk + placeholder | `.toolResultEviction(...)` | [Context management](/dev/en/docs/harness/context) |
| Repeated tool failures | One short reminder after the model repeats the same failing call | on by default; `.disableRepeatedFailureGuard()` | below |
| Subagent orchestration | Delegate to children, sync or background, with auto push-back | `.subagent(...)` or drop spec in `workspace/subagents/` | [Subagent](/dev/en/docs/harness/subagent) |
| Pluggable filesystem | Local + shell / shared store / sandbox without code changes | `.filesystem(...)` | [Filesystem](/dev/en/docs/harness/filesystem) |
| Sandbox isolation | Files and commands isolated; cross-call recovery; multi-replica | `.filesystem(new DockerFilesystemSpec()...)` | [Sandbox](/dev/en/docs/harness/sandbox) |
| Plan Mode | Read-only think-first phase with HITL exit | `.enablePlanMode()` | [Plan Mode](/dev/en/docs/harness/plan-mode) |
| Skill composition | Skills from Git / Nacos / MySQL / classpath / workspace | `.skillRepository(...)` | [Skill](/dev/en/docs/harness/skill) |
| MCP integration & tool allowlist | Declarative MCP servers + allow/deny per tool | `workspace/tools.json` | [Workspace](/dev/en/docs/harness/workspace) |
| Channel routing | Session management, per-session concurrency, multi-agent routing, streaming events | `agent.channel(...)` / `GatewayBootstrap` | [Channel](/dev/en/docs/harness/channel) |

## How state flows

Three layers exist; the framework moves data between them automatically.

- **In-call state** — `AgentState` (conversation context, permission rules, Plan Mode state, tool state) plus `RuntimeContext` (`sessionId`, `userId`, sandbox handle, extras).
- **Cross-call state** — Default recovery uses native checkpoints and applicable facts; full history remains and JSONL is exported only on demand. snapshot mode use AgentStateStore; files, tasks and sandbox metadata retain their own backends.
- **Long-term memory** — accumulated across sessions: `memory/YYYY-MM-DD.md` is append-only, periodically merged into `MEMORY.md` by a throttled background job; `MEMORY.md` loads once per call as reference context, not System instructions.

Three invariants worth remembering:

- Final requests are rebuilt each reasoning step, but workspace files load per call. Edits to AGENTS.md or MEMORY.md take effect on the next call without restarting.
- Compaction, memory distillation, and background maintenance are throttled; they don't run every turn.
- Core manages persistence: journal mode commits checkpoints; snapshot mode save legacy state. Harness selects the default log backend.

## Repeated tool failures

Built-in tools classify failures in `ToolResultBlock.metadata`:

- `agentscope_error_kind` — a `ToolErrorKind` value such as `not_found`, `invalid_argument`, `permission_denied` or `timeout`.
- `agentscope_error_scope` — the failed object: a workspace-relative path, host, thread id, and so on.
- `agentscope_retriable` — whether repeating the identical call may succeed; absence means unknown.

Core also classifies tool lookup, availability and argument-validation failures, using the tool name as scope. Missing and ungranted tools share `not_found` text and metadata so tool existence is not disclosed; inactive tools use `permission_denied`, and malformed or schema-invalid arguments use `invalid_argument`. These control-plane fields reach `ToolResultEndEvent` without changing the model-visible error text.

Custom tools can opt in with `ToolErrorMetadata.of(kind, scope)` when building an error result.

`HarnessAgent` installs a `RepeatedFailureGuard` that observes tool events at the single producer-side `onAgentEvent` notification point, including malformed-argument failures rejected before execution. Observation does not depend on `ON_AGENT` stream-consumer demand. The parent guard filters forwarded child sources; each child's own guard observes its local events before forwarding attaches the source tag. The last eight classified failures are counted by tool, kind and scope: three occurrences trigger a short reminder in the next model request (five for retriable failures). Success clears failures for the same arguments, or a matching scope when arguments are unavailable, not every failure of that tool. `command_failed` reminders direct the model to inspect exit codes and diagnostic output before changing the command.

Reminders do not distinguish a missing tool from an ungranted tool of the same name, or add a tool catalog to the model's input. When an actual result reports `permission_denied`, the reminder tells the model to stop the current operation rather than change tools, identity or paths to bypass the denial. Continuing that operation requires authorization through the application's permission flow, not another route to the same effect.

A separate eight-result window fingerprints identical arguments and streamed output, including `SUCCESS` / exit 0 and unclassified errors. Three repetitions (five when marked retriable) produce a neutral reminder that does not imply failure; changed results reset that call's repetition history. For shell results with `agentscope_output_sha256` metadata, comparison uses the digest of the captured raw streams, so random archive paths and display notices do not hide repeated output. Without that digest, comparison retains exact result hashing. Fingerprints retain hashes, not raw inputs or output. Both paths share a maximum of two reminders per turn, with three further occurrences before a second reminder for a signature. Reminders are not saved to conversation history; a new turn or call completion resets both windows. The guard never retries, rewrites arguments or stops the run, and does not parse business errors out of text. Call `.disableRepeatedFailureGuard()` to turn it off, or register middleware for another policy.

The built-in context conventions also require checking current state before retrying side-effectful actions with uncertain outcomes and retrieving the original source before quoting it verbatim. These are model guidance, not runtime idempotency enforcement or permission grants.

## Adding your own middleware

To insert custom behaviour without bypassing Harness's plumbing:

- Use `.middleware(...)` — your middleware runs before all Harness built-ins.
- Read `RuntimeContext` from the agent for the current call's identity (`userId` / `sessionId`).
- For workspace I/O, go through `harnessAgent.getWorkspaceManager()` — it routes correctly under sandbox or remote-store modes. `java.nio.Files` writes to the host disk and will land in the wrong place outside local mode.

## Related pages

- [Session operations, events and recovery](/dev/en/docs/harness/session-log) — background tasks, queues, interaction and continuation
- [Recoverable chat example](/dev/en/blogs/best-practices/session-chat) — a complete application from submission to frontend reconnection
- [Workspace](/dev/en/docs/harness/workspace) — directory layout, instruction and reference sources, `tools.json`
- [Context & AgentState](/dev/en/docs/building-blocks/context) — `AgentState`, `RuntimeContext`, `AgentStateStore` persistence, multi-user isolation
- [Memory](/dev/en/docs/harness/memory) — two-layer memory
- [Context management](/dev/en/docs/harness/context) — build model input, track long-running tasks, summarize history, and offload large results
- [Filesystem](/dev/en/docs/harness/filesystem) — local + shell / shared store / sandbox
- [Sandbox](/dev/en/docs/harness/sandbox) — isolated execution, cross-call recovery, distributed
- [Subagent](/dev/en/docs/harness/subagent) — declarations, sync/background, streaming forwarding
- [Skill](/dev/en/docs/harness/skill) — four-layer composition, self-learning loop
- [Plan Mode](/dev/en/docs/harness/plan-mode) — read-only phase + HITL exit
- [Channel](/dev/en/docs/harness/channel) — session management, multi-agent routing, streaming SSE

## Final model input construction

Harness organizes System instructions, the skill catalog, conversation, transient state, and references at the final model-call boundary. Supply dynamic business information through `contextSource`. See [Context management](/dev/en/docs/harness/context) for configuration, defaults and limits.
