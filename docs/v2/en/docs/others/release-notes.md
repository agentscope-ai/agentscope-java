---
title: Release Notes
description: Per-version change records for AgentScope Java
zh_link: /v2/zh/docs/others/release-notes
---

This page tracks per-version changes for AgentScope Java 2.0. For the overall migration guide from 1.x, see the [V1 Migration Guide](/v2/en/docs/change-log).

---

## 2.0.4

> Status: Release draft (prepared on 2026-10-08)

AgentScope Java 2.0.4 adds OpenAI Responses API support, a redesigned AgentScope Service, new channel and storage integrations, and stronger isolation for concurrent agent runs, tools, and skills.

### Added

**Core / Models**

- Add the OpenAI Responses API module ([#3078](https://github.com/agentscope-ai/agentscope-java/pull/3078)).
- Support `returnDirect` tool results to short-circuit the reasoning loop ([#2891](https://github.com/agentscope-ai/agentscope-java/pull/2891)).
- Expose detailed chat usage token metrics and model-fallback failover listeners ([#3215](https://github.com/agentscope-ai/agentscope-java/pull/3215), [#3145](https://github.com/agentscope-ai/agentscope-java/pull/3145)).
- Support Anthropic bearer token authentication ([#3038](https://github.com/agentscope-ai/agentscope-java/pull/3038)).
- Add a type-safe Jev System One client, Spring Boot starter, and example middlewares ([#3240](https://github.com/agentscope-ai/agentscope-java/pull/3240)).

**Harness / Tools / Storage**

- Add per-run execution control with `AgentRun` / `RunControl` and explicit runtime context propagation ([#3279](https://github.com/agentscope-ai/agentscope-java/pull/3279)).
- Add request-scoped tool configuration and isolate tool visibility, streaming callbacks, and session skill resources ([#3283](https://github.com/agentscope-ai/agentscope-java/pull/3283)).
- Add opt-in `sharedLocalWorkspace` to filesystem specs ([#3247](https://github.com/agentscope-ai/agentscope-java/pull/3247)).
- Allow disabling built-in web tools and injecting an HTTP client into them ([#3075](https://github.com/agentscope-ai/agentscope-java/pull/3075), [#3103](https://github.com/agentscope-ai/agentscope-java/pull/3103)).
- Add configurable MCP metadata propagation per server and tool ([#3255](https://github.com/agentscope-ai/agentscope-java/pull/3255)).
- Add MongoDB storage extension ([#2698](https://github.com/agentscope-ai/agentscope-java/pull/2698)).
- Automatically prune old E2B native snapshots per sandbox ([#2555](https://github.com/agentscope-ai/agentscope-java/pull/2555)).

**Service / Channels**

- Introduce the redesigned AgentScope Service implementation ([#3080](https://github.com/agentscope-ai/agentscope-java/pull/3080)).
- Add Personal Weixin iLink channel integration ([#3184](https://github.com/agentscope-ai/agentscope-java/pull/3184)).
- Add DingTalk HTTP callback receive mode alongside Stream mode ([#3177](https://github.com/agentscope-ai/agentscope-java/pull/3177)).
- Add pluggable channel access-token storage and inbound-event deduplication, with idle eviction for bot-loop guards ([#3182](https://github.com/agentscope-ai/agentscope-java/pull/3182), [#3175](https://github.com/agentscope-ai/agentscope-java/pull/3175)).
- Expose inbound channel media messages as neutral metadata ([#3180](https://github.com/agentscope-ai/agentscope-java/pull/3180)).

### Changed

- Remove the unused message role parameter ([#3045](https://github.com/agentscope-ai/agentscope-java/pull/3045)).
- Pass Gemini tool parameter schemas directly as native JSON Schema ([#3051](https://github.com/agentscope-ai/agentscope-java/pull/3051)).
- Derive `Version.VERSION` from the Maven project version ([#3087](https://github.com/agentscope-ai/agentscope-java/pull/3087)).
- Migrate the documentation site to Mintlify while preserving legacy URLs and restoring hosted language switching ([#3081](https://github.com/agentscope-ai/agentscope-java/pull/3081), [#3082](https://github.com/agentscope-ai/agentscope-java/pull/3082), [#3276](https://github.com/agentscope-ai/agentscope-java/pull/3276)).

### Fixed

**Core / State / Concurrency**

- Isolate concurrent invocation contexts and cancellation of individual running or queued executions ([#3279](https://github.com/agentscope-ai/agentscope-java/pull/3279)); prevent cross-session tool and skill interference ([#3283](https://github.com/agentscope-ai/agentscope-java/pull/3283)).
- Fix version-0 state migration CAS conflicts, atomically return JDBC unconditional-write versions, and cache versions after unconditional writes ([#3165](https://github.com/agentscope-ai/agentscope-java/pull/3165), [#3220](https://github.com/agentscope-ai/agentscope-java/pull/3220), [#3236](https://github.com/agentscope-ai/agentscope-java/pull/3236)).
- Evict slot versions with the state cache, avoid deserializing the `State` marker interface, and persist state after empty agent results ([#3074](https://github.com/agentscope-ai/agentscope-java/pull/3074), [#3048](https://github.com/agentscope-ai/agentscope-java/pull/3048), [#3049](https://github.com/agentscope-ai/agentscope-java/pull/3049)).
- Preserve failed summary termination state and handle synchronous model-fallback failures ([#2757](https://github.com/agentscope-ai/agentscope-java/pull/2757), [#3149](https://github.com/agentscope-ai/agentscope-java/pull/3149)).
- Emit tool-result events for denied HITL calls and support custom denial reasons ([#3104](https://github.com/agentscope-ai/agentscope-java/pull/3104), [#2546](https://github.com/agentscope-ai/agentscope-java/pull/2546)).
- Make JSON Schema generation thread-safe ([#2796](https://github.com/agentscope-ai/agentscope-java/pull/2796)).

**Model Providers**

- Default plaintext HTTP requests to HTTP/1.1 to prevent h2c upgrades from losing POST bodies with vLLM / uvicorn ([#3214](https://github.com/agentscope-ai/agentscope-java/pull/3214)).
- Preserve reasoning details from all streaming chunks and thought signatures across JSON persistence round-trips; remove redundant OpenAI tool metadata ([#3128](https://github.com/agentscope-ai/agentscope-java/pull/3128), [#2910](https://github.com/agentscope-ai/agentscope-java/pull/2910), [#2914](https://github.com/agentscope-ai/agentscope-java/pull/2914)).
- Move cache control to content blocks ([#2878](https://github.com/agentscope-ai/agentscope-java/pull/2878)).
- Decouple DashScope streaming from thinking mode, allow custom multimodal endpoint detection, and offload blocking embedding SDK calls ([#3137](https://github.com/agentscope-ai/agentscope-java/pull/3137), [#3163](https://github.com/agentscope-ai/agentscope-java/pull/3163), [#3265](https://github.com/agentscope-ai/agentscope-java/pull/3265)).
- Correct Gemini token usage accounting ([#3034](https://github.com/agentscope-ai/agentscope-java/pull/3034)).
- Serialize Ollama tool-result names as `tool_name` and expose thinking output as `ThinkingBlock` ([#3093](https://github.com/agentscope-ai/agentscope-java/pull/3093), [#3146](https://github.com/agentscope-ai/agentscope-java/pull/3146)).

**Harness / Sandbox / Integrations**

- Preserve non-UTF-8 file uploads and expose session workspace paths in prompts ([#2456](https://github.com/agentscope-ai/agentscope-java/pull/2456), [#3020](https://github.com/agentscope-ai/agentscope-java/pull/3020)).
- Rebind Kubernetes remote snapshots on state resume and preserve wrapping newlines in commands ([#3025](https://github.com/agentscope-ai/agentscope-java/pull/3025), [#3204](https://github.com/agentscope-ai/agentscope-java/pull/3204)).
- Improve Docker sandbox native file transfer and reject truncated downloads ([#2923](https://github.com/agentscope-ai/agentscope-java/pull/2923)).
- Keep MCP connections open for AgentRun sandboxes that are not owned by the caller; preserve image URLs in MCP tool results ([#2302](https://github.com/agentscope-ai/agentscope-java/pull/2302), [#3053](https://github.com/agentscope-ai/agentscope-java/pull/3053)).
- Allow path separators in JDBC sandbox-shaped slot IDs ([#3233](https://github.com/agentscope-ai/agentscope-java/pull/3233)).
- Preserve AG-UI tool results after permission resume, assign unique IDs to discontinuous text segments, and clear active-run markers before terminal signals ([#3100](https://github.com/agentscope-ai/agentscope-java/pull/3100), [#3010](https://github.com/agentscope-ai/agentscope-java/pull/3010), [#3109](https://github.com/agentscope-ai/agentscope-java/pull/3109)).
- Propagate WeCom send rejections as stream errors ([#3173](https://github.com/agentscope-ai/agentscope-java/pull/3173)).
- Use modular core and AG-UI dependencies in Spring Boot starters ([#3156](https://github.com/agentscope-ai/agentscope-java/pull/3156)).
- Normalize Milvus L2 retrieval scores and escape WordReader Markdown table cells ([#3070](https://github.com/agentscope-ai/agentscope-java/pull/3070), [#3169](https://github.com/agentscope-ai/agentscope-java/pull/3169)).

### Documentation

- Add bilingual Agent Harness building guides and rebuild AgentScope Service product guides ([#3139](https://github.com/agentscope-ai/agentscope-java/pull/3139), [#3136](https://github.com/agentscope-ai/agentscope-java/pull/3136)).
- Document Jev structured decision integration and correct the documented `CompactionConfig.keepTokens` default ([#3274](https://github.com/agentscope-ai/agentscope-java/pull/3274), [#3144](https://github.com/agentscope-ai/agentscope-java/pull/3144)).

**Full changelog:** [v2.0.3...v2.0.4](https://github.com/agentscope-ai/agentscope-java/compare/v2.0.3...v2.0.4)

---

## 2.0.3

> Released: 2026-09-07

**GitHub Release:** [v2.0.3](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.3)

This release introduces Anthropic prompt caching, a `deliver_artifact` tool for sandboxed agents, a `FinalAnswerFilterMiddleware` for ReAct streams, state versioning and optimistic concurrency primitives for agent state stores, an `AgentProtocolEventBus` for pluggable SSE event handling, and a console transcript overhaul, and includes a broad set of reliability fixes across the core reasoning loop, harness, sandbox, and AG-UI protocol layers.

**Quick links:** [Quickstart](/v2/en/docs/quickstart) | [V1 Migration Guide](/v2/en/docs/change-log) | [Going to Production](/v2/en/docs/others/going-to-production)

### Added

**Core / Agent**

- Propagate `ToolResultBlock.metadata` through fine-grained v2 tool-result events (`ToolResultTextDeltaEvent` / `ToolResultDataDeltaEvent` / `ToolResultEndEvent`) so event-stream consumers can access tool-specific context ([#2315](https://github.com/agentscope-ai/agentscope-java/pull/2315))
- Introduce `AgentProtocolEventBus` for pluggable SSE event handling in the agent-protocol layer ([#2634](https://github.com/agentscope-ai/agentscope-java/pull/2634))
- State versioning and optimistic concurrency primitives — `VersionedState`, `ConflictPolicy`, and `ConcurrentSessionModificationException` — enabling `AgentStateStore` implementations to detect and reject stale writes

**Middleware**

- `FinalAnswerFilterMiddleware` — an opt-in filter that buffers text events per model call, suppresses intermediate reasoning-round text when a tool call is produced, and emits only the final user-facing answer ([#2926](https://github.com/agentscope-ai/agentscope-java/pull/2926), [#2872](https://github.com/agentscope-ai/agentscope-java/issues/2872))

**Model Providers**

- Anthropic prompt caching: set `cache_control` breakpoints on tools, system, and the last message; surface `cache_read_input_tokens` and `cache_creation_input_tokens` in usage ([#2350](https://github.com/agentscope-ai/agentscope-java/pull/2350), [#2223](https://github.com/agentscope-ai/agentscope-java/issues/2223))
- Anthropic + Gemini `ResponseParser` read `cachedTokens` into usage ([#2568](https://github.com/agentscope-ai/agentscope-java/pull/2568))
- Explicit no-cache semantics: `CACHE_CONTROL=false` metadata maps to `{"type":"no_cache"}` for OpenAI and DashScope converters, so a single message can opt out of caching ([#2685](https://github.com/agentscope-ai/agentscope-java/pull/2685), [#2684](https://github.com/agentscope-ai/agentscope-java/issues/2684))
- `dashscope_image_to_image` (image editing) tool for `DashScopeMultiModalTool` — lets an agent edit a user-provided image via a prompt instead of redrawing from a textual description with `dashscope_text_to_image` ([#2995](https://github.com/agentscope-ai/agentscope-java/pull/2995))

**Harness / Tools**

- `deliver_artifact` tool for sandboxed agents — an `ArtifactDeliveryTarget` SPI + tool that lets an agent inside a sandbox hand out produced files; the workspace prompt now references it when a target is configured, and states plainly that no cross-boundary mechanism exists otherwise ([#2667](https://github.com/agentscope-ai/agentscope-java/pull/2667), [#2663](https://github.com/agentscope-ai/agentscope-java/issues/2663))
- MCP server registration results — an optional `McpServerRegistrationListener` with `SUCCESS` / `FAILED` / `SKIPPED` terminal states, so host services can identify and retire unhealthy MCP configurations ([#2877](https://github.com/agentscope-ai/agentscope-java/pull/2877), [#2875](https://github.com/agentscope-ai/agentscope-java/issues/2875))
- `description` field on `SubagentFactoryEntry` so the orchestrator gets useful subagent selection context instead of a bare name ([#1506](https://github.com/agentscope-ai/agentscope-java/pull/1506), [#1504](https://github.com/agentscope-ai/agentscope-java/issues/1504))
- Public `Toolkit` API to assign an already-registered tool to an additional group ([#2836](https://github.com/agentscope-ai/agentscope-java/pull/2836), [#2835](https://github.com/agentscope-ai/agentscope-java/issues/2835))
- Stream ranged file reads in `ReadFileTool` — positive line ranges are read incrementally and stop at the requested end line, avoiding loading the full file into memory ([#2402](https://github.com/agentscope-ai/agentscope-java/pull/2402))

**AG-UI**

- CopilotKit + AG-UI full-stack example covering threads, shared state, generative UI, A2UI workbench, and HITL flows end-to-end ([#2554](https://github.com/agentscope-ai/agentscope-java/pull/2554))
- Configure agent interruption on AG-UI disconnect ([#2719](https://github.com/agentscope-ai/agentscope-java/pull/2719), [#2715](https://github.com/agentscope-ai/agentscope-java/issues/2715))

**Console**

- Transcript overhaul — one bubble per user question, with text and tool calls rendered as ordered content blocks in chronological order; `react-markdown` rendering, syntax-highlighted tool I/O cards, SSE auto-reconnect with exponential backoff, and `session.error` rendering ([#2640](https://github.com/agentscope-ai/agentscope-java/pull/2640))

**Examples**

- User binding preferences CRUD API for the DataAgent example ([#2711](https://github.com/agentscope-ai/agentscope-java/pull/2711))
- v2 application-layer RAG example ([#2794](https://github.com/agentscope-ai/agentscope-java/pull/2794))

### Refactored

- Move `AguiRuntimeContextRequest` / `AguiRuntimeContextResolver` / `AguiRequestBodyParser` down from the example layer into the `extensions-agui` protocol layer ([#2822](https://github.com/agentscope-ai/agentscope-java/pull/2822))
- Replace the Java service control plane with the Go `service-controlplane` control plane, keeping the Java gateway, data, and scheduler planes; the `agentscope-builder` example is promoted to the top-level `agentscope-service` module
- Isolate HITL sessions by user — key `ThreadSessionManager` / `AgentResolver` by `(userId, threadId)` so `hasMemory` and agent reuse no longer mix tenants, and unwrap harness/stop interrupts via `AguiUtil.asReActAgent` so demo `stopThread` and processor interrupts target the live session ([#2856](https://github.com/agentscope-ai/agentscope-java/pull/2856), [#2855](https://github.com/agentscope-ai/agentscope-java/issues/2855))
- Introduce `agentscope-extensions-jdbc` with a dialect abstraction (`AbstractJdbcDialect` / `StoreDialect` / `SessionStateDialect` / `SnapshotDialect`) and vendor implementations for MySQL, PostgreSQL, H2, and SQLite; the existing `MysqlDistributedStore` and `PostgresDistributedStore` now delegate to the unified JDBC module ([#2759](https://github.com/agentscope-ai/agentscope-java/pull/2759), [#2503](https://github.com/agentscope-ai/agentscope-java/issues/2503))

### Fixed

**Core / Agent**

- Propagate `ChatResponse.metadata` to `Msg.metadata` in `ReasoningContext` ([#2931](https://github.com/agentscope-ai/agentscope-java/pull/2931))
- Propagate agent state load failures instead of silently replacing conversation state with a fresh session on backend / I/O / decoding errors ([#2760](https://github.com/agentscope-ai/agentscope-java/pull/2760))
- Preserve caller-supplied permission context when loading legacy v1 session state, so 1.x → 2.0 migration does not silently downgrade to `DEFAULT` permission mode ([#2769](https://github.com/agentscope-ai/agentscope-java/pull/2769), [#2768](https://github.com/agentscope-ai/agentscope-java/issues/2768))
- Retry empty final responses instead of finishing silently when a reasoning model emits its answer into `reasoning_content` with empty `content` ([#2755](https://github.com/agentscope-ai/agentscope-java/pull/2755), [#2750](https://github.com/agentscope-ai/agentscope-java/issues/2750))
- Persist the current turn's user input and safe context on model call failure so a resumed session can see the last question ([#2799](https://github.com/agentscope-ai/agentscope-java/pull/2799))
- Reconcile dangling `tool_use` blocks on interrupt before persisting `AgentState`, fixing a window between reasoning and acting where pending tool calls were left unmatched ([#2410](https://github.com/agentscope-ai/agentscope-java/pull/2410), [#2409](https://github.com/agentscope-ai/agentscope-java/issues/2409))
- Return suspended results for external tools instead of converting them to generic errors; emit `RequireExternalExecutionEvent` for suspended tool calls ([#1668](https://github.com/agentscope-ai/agentscope-java/pull/1668), [#1582](https://github.com/agentscope-ai/agentscope-java/issues/1582))
- Emit `ExternalExecutionResultEvent` when external tool results resume ([#2605](https://github.com/agentscope-ai/agentscope-java/pull/2605))
- Restore event emitter for detached tool calls ([#2483](https://github.com/agentscope-ai/agentscope-java/pull/2483))
- Include field path in tool validation error messages ([#2718](https://github.com/agentscope-ai/agentscope-java/pull/2718))
- Simplify `ReActAgent` pending tool and error result handling ([#2666](https://github.com/agentscope-ai/agentscope-java/pull/2666))
- Normalize model-call tools in middleware to avoid duplicate or malformed tool definitions ([#2756](https://github.com/agentscope-ai/agentscope-java/pull/2756))
- Avoid event-loop blocking in `WorkspaceContextMiddleware#onSystemPrompt` ([#2632](https://github.com/agentscope-ai/agentscope-java/pull/2632))
- Use call-scoped `AgentState` for `ReActAgent` shutdown retry recovery so the `shutdownInterrupted` flag is checked and cleared against the per-session state for the current `(userId, sessionId)` call, instead of the default session state ([#2712](https://github.com/agentscope-ai/agentscope-java/pull/2712), [#2708](https://github.com/agentscope-ai/agentscope-java/issues/2708))
- Preserve `Msg.usage` in structured-output responses — both the native structured-output path and the fallback path now propagate token usage instead of leaving `Msg.getUsage()` null ([#2966](https://github.com/agentscope-ai/agentscope-java/pull/2966))
- Unblock backpressured SSE streams in `OkHttpTransport` — use `subscribeOn(Schedulers.boundedElastic(), false)` so downstream demand signals are no longer queued behind the blocking `readLine()` loop, allowing incremental SSE event delivery before `[DONE]` is received ([#2963](https://github.com/agentscope-ai/agentscope-java/pull/2963))

**Model Providers**

- Gemini: apply `ModelUtils.applyTimeoutAndRetry` to response streams so configured timeout and retry settings take effect ([#2356](https://github.com/agentscope-ai/agentscope-java/pull/2356))
- RAGFlow: preserve final retry response body so callers can read error details ([#2631](https://github.com/agentscope-ai/agentscope-java/pull/2631))
- RAGFlow: type `rerankId` as `String` to match the RAGFlow API ([#2776](https://github.com/agentscope-ai/agentscope-java/pull/2776))
- DashScope: route Qwen3.8 variants (`qwen3.8-max`, `qwen3.8-flash`, `qwen3.8-27b`) to the multimodal API instead of the text-generation endpoint ([#2987](https://github.com/agentscope-ai/agentscope-java/pull/2987))

**Harness / Tools / Sandbox**

- Stop skill-cache orphan GC from deleting live directories ([#2840](https://github.com/agentscope-ai/agentscope-java/pull/2840), [#2787](https://github.com/agentscope-ai/agentscope-java/issues/2787))
- Make Nacos skill source paths Windows-safe ([#2921](https://github.com/agentscope-ai/agentscope-java/pull/2921))
- Make memory flush fire-and-forget to unblock conversation completion; add `HarnessBackgroundTaskQuiescenceExtension` so tests drain background flush before `@TempDir` teardown ([#2777](https://github.com/agentscope-ai/agentscope-java/pull/2777), [#2935](https://github.com/agentscope-ai/agentscope-java/pull/2935))
- Handle `SIGTERM` in Docker keep-alive to avoid 30s stop delay ([#2885](https://github.com/agentscope-ai/agentscope-java/pull/2885))
- Bound filesystem search tool output size ([#2832](https://github.com/agentscope-ai/agentscope-java/pull/2832))
- Isolate sandbox binding per call to fix concurrent corruption ([#2675](https://github.com/agentscope-ai/agentscope-java/pull/2675))
- Make concurrent sandbox uploads safe — unique hydrate temp names and native transfer for relative paths ([#2762](https://github.com/agentscope-ai/agentscope-java/pull/2762))
- Fix Windows Docker sandbox session file upload via tar stream ([#2557](https://github.com/agentscope-ai/agentscope-java/pull/2557))
- E2B: preserve zero exit code in JSON stream ([#2609](https://github.com/agentscope-ai/agentscope-java/pull/2609)); reject incomplete process streams without exit code ([#2828](https://github.com/agentscope-ai/agentscope-java/pull/2828)); reset projection state when recreating sandbox ([#2586](https://github.com/agentscope-ai/agentscope-java/pull/2586))
- Kubernetes sandbox: bump fabric8 to 7.8.0 to fix watch NPE with Jackson 2.19+ ([#2766](https://github.com/agentscope-ai/agentscope-java/pull/2766)); follow redirects so file API downloads survive gateway 307 ([#2748](https://github.com/agentscope-ai/agentscope-java/pull/2748))
- Keep persisted snapshot id when resume falls back to fresh create ([#2775](https://github.com/agentscope-ai/agentscope-java/pull/2775))
- Add reply IDs to subagent lifecycle events ([#2680](https://github.com/agentscope-ai/agentscope-java/pull/2680))
- Inherit memory config in subagents ([#2611](https://github.com/agentscope-ai/agentscope-java/pull/2611))
- Make orphan sweep timeout boundary inclusive ([#2619](https://github.com/agentscope-ai/agentscope-java/pull/2619))
- Do not downgrade a user-interrupted session to a compaction failure ([#2659](https://github.com/agentscope-ai/agentscope-java/pull/2659))
- Inherit pending tool recovery in subagents — propagate `enablePendingToolRecovery` from the parent `HarnessAgent` to declared and built-in general-purpose subagents, so dangling tool calls are recovered instead of persisting and causing subsequent requests to fail ([#3017](https://github.com/agentscope-ai/agentscope-java/pull/3017), [#3016](https://github.com/agentscope-ai/agentscope-java/issues/3016))
- Count `ThinkingBlock` tokens from thinking content instead of using the fixed fallback overhead; also count thinking content nested inside `ToolResultBlock` ([#3009](https://github.com/agentscope-ai/agentscope-java/pull/3009), [#1525](https://github.com/agentscope-ai/agentscope-java/issues/1525))
- Isolate memory flush and maintenance throttles — give each periodic operation a distinct gate key so they no longer suppress each other despite having separate configured intervals ([#2993](https://github.com/agentscope-ai/agentscope-java/pull/2993))
- Fail sandbox filesystem read ops (`ls` / `read` / `grep` / `glob`) on non-successful `execute()` responses instead of masking errors as empty results or fabricated file paths ([#2967](https://github.com/agentscope-ai/agentscope-java/pull/2967), [#2961](https://github.com/agentscope-ai/agentscope-java/issues/2961))
- Prevent pipe deadlock in `LocalFilesystemWithShell.execute()` — drain child process stdout/stderr on daemon threads concurrently with `Process.waitFor()` instead of only after, so commands that exceed the OS pipe buffer are no longer misreported as timeouts ([#2839](https://github.com/agentscope-ai/agentscope-java/pull/2839))
- Preserve blank lines between paragraphs in `WordReader` — empty `<w:p>` elements are now emitted as `\n` instead of being silently discarded ([#2965](https://github.com/agentscope-ai/agentscope-java/pull/2965), [#2964](https://github.com/agentscope-ai/agentscope-java/issues/2964))
- Fix reading of historical sessions of the data agent in examples — align sandbox write/read agent IDs and ensure conversation content is flushed to the sandbox so history sessions render correctly ([#2946](https://github.com/agentscope-ai/agentscope-java/pull/2946), [#2735](https://github.com/agentscope-ai/agentscope-java/issues/2735))
- Bump MCP SDK from `0.17.0` to `0.17.2` so `HttpClientStreamableHttpTransport` accepts MCP servers that reply to `initialize` / `notifications/initialized` with `202 Accepted` + `text/plain` + chunked empty body instead of throwing `Unknown media type: text/plain; charset=utf-8` ([#2958](https://github.com/agentscope-ai/agentscope-java/pull/2958))

**AG-UI**

- Assign per-tool result message ids ([#2908](https://github.com/agentscope-ai/agentscope-java/pull/2908))
- Emit frontend tool args from fragment deltas ([#2874](https://github.com/agentscope-ai/agentscope-java/pull/2874))
- Isolate HITL sessions by user ([#2856](https://github.com/agentscope-ai/agentscope-java/pull/2856))
- Emit AG-UI interrupt for permission-type HITL tool confirmation ([#2495](https://github.com/agentscope-ai/agentscope-java/pull/2495), [#2437](https://github.com/agentscope-ai/agentscope-java/issues/2437))
- Parse request bodies with Jackson 2 codec for Boot 4 / multimodal `MessageContent` ([#2638](https://github.com/agentscope-ai/agentscope-java/pull/2638))
- Suppress `ReActAgent` handshake events in AG-UI converters ([#2639](https://github.com/agentscope-ai/agentscope-java/pull/2639))
- Stop emitting `RUN_FINISHED` after `RUN_ERROR` by default ([#2646](https://github.com/agentscope-ai/agentscope-java/pull/2646))
- Cancel MVC subscription on disconnect ([#2786](https://github.com/agentscope-ai/agentscope-java/pull/2786))
- Dedupe resume tool results already present in messages ([#2955](https://github.com/agentscope-ai/agentscope-java/pull/2955))

**Protocol**

- Clear task submit context before publishing terminal status to close an `await` race in `AgentProtocolTaskStore` ([#2802](https://github.com/agentscope-ai/agentscope-java/pull/2802))

**Storage**

- MySQL: remove path-separator check from `MysqlAgentStateStore` session id validation ([#2022](https://github.com/agentscope-ai/agentscope-java/pull/2022))

**Console / Frontend**

- Allow owners to edit agent settings and add model field ([#2630](https://github.com/agentscope-ai/agentscope-java/pull/2630))
- Render `session.error` events in managed session chat ([#2598](https://github.com/agentscope-ai/agentscope-java/pull/2598), [#2596](https://github.com/agentscope-ai/agentscope-java/issues/2596))
- Add cache-control headers to console static serving ([#2607](https://github.com/agentscope-ai/agentscope-java/pull/2607))

---

## 2.0.2

> Released: 2026-09-03

**GitHub Release:** [v2.0.2](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.2)

AgentScope Java 2.0.2 improves runtime context propagation and remote subagent event streaming, and decouples agent-protocol task routing and storage from execution workspaces.

### Added

- Route agent-protocol tasks through `AgentFactory` ([#2590](https://github.com/agentscope-ai/agentscope-java/pull/2590)).
- Support forcing synchronous `agent_spawn` execution through `RuntimeContext` ([#2592](https://github.com/agentscope-ai/agentscope-java/pull/2592)).
- Stamp `parentSessionId` on remote subagent events ([#2593](https://github.com/agentscope-ai/agentscope-java/pull/2593)).
- Pass caller context attributes into agent-protocol task runs ([#2595](https://github.com/agentscope-ai/agentscope-java/pull/2595)).
- Allow callers to pass `RuntimeContext` into Channel `Gateway` ([#2604](https://github.com/agentscope-ai/agentscope-java/pull/2604)).
- Forward the full remote subagent event stream ([#2613](https://github.com/agentscope-ai/agentscope-java/pull/2613)).

### Refactored

- Decouple agent-protocol `TaskStore` from the execution `WorkspaceManager` ([#2615](https://github.com/agentscope-ai/agentscope-java/pull/2615)).

---

## 2.0.1

> Released: 2026-08-05

AgentScope Java 2.0.1 is the first maintenance release after 2.0.0 GA. It expands the model-provider ecosystem, hardens Harness subagent / HITL / permission behavior, and fixes a set of production-critical issues.

**Quick links:** [Quickstart](/v2/en/docs/quickstart) | [V1 Migration Guide](/v2/en/docs/change-log) | [Going to Production](/v2/en/docs/others/going-to-production)

### Added

**Core / Agent**

- Middleware execution ordering via `MiddlewareBase.order()` (higher values wrap outer); `ReActAgent.Builder.build()` stably sorts descending after all registrations ([#2532](https://github.com/agentscope-ai/agentscope-java/pull/2532), [#2449](https://github.com/agentscope-ai/agentscope-java/issues/2449))
- Session context clear API on `ReActAgent` / `HarnessAgent` to clear model-visible conversation context without creating a new session ([#2499](https://github.com/agentscope-ai/agentscope-java/pull/2499), [#2496](https://github.com/agentscope-ai/agentscope-java/issues/2496))
- Expose `ReActAgent` state-cache cleanup APIs for long-lived instances ([#2572](https://github.com/agentscope-ai/agentscope-java/pull/2572))
- Emit `UserConfirmResultEvent` when resuming permission HITL, correlatable with the prior `RequireUserConfirmEvent` via `replyId` ([#2511](https://github.com/agentscope-ai/agentscope-java/pull/2511))
- Anthropic: support configuring `disable_parallel_tool_use` ([#2257](https://github.com/agentscope-ai/agentscope-java/pull/2257))

**Model Providers**

- Add OpenAI-compatible extension package as a shared base for third-party compatible vendors ([#2208](https://github.com/agentscope-ai/agentscope-java/pull/2208))
- Add DeepSeek as a first-class model provider (`deepseek:<model>`, `DEEPSEEK_API_KEY`) ([#2307](https://github.com/agentscope-ai/agentscope-java/pull/2307), [#2211](https://github.com/agentscope-ai/agentscope-java/issues/2211))
- Add GLM (Zhipu AI) provider and dedicated formatters ([#2316](https://github.com/agentscope-ai/agentscope-java/pull/2316))
- Add Kimi (Moonshot AI) provider and dedicated formatters ([#2320](https://github.com/agentscope-ai/agentscope-java/pull/2320), [#2213](https://github.com/agentscope-ai/agentscope-java/issues/2213))
- Add MiniMax OpenAI-compatible provider ([#2299](https://github.com/agentscope-ai/agentscope-java/pull/2299))

**Harness / Tools**

- Remote subagent event streaming and HITL resume ([#2559](https://github.com/agentscope-ai/agentscope-java/pull/2559))
- Wait for async tool results by `taskId` ([#2529](https://github.com/agentscope-ai/agentscope-java/pull/2529))
- Default workspace via `AGENTSCOPE_WORKSPACE` env var for image packaging ([#2310](https://github.com/agentscope-ai/agentscope-java/pull/2310))

**AG-UI**

- Upgrade AG-UI module event mechanism ([#2306](https://github.com/agentscope-ai/agentscope-java/pull/2306), [#2202](https://github.com/agentscope-ai/agentscope-java/issues/2202))
- Introduce typed `MessageContent` / `InputContent` for multimodal AG-UI messages ([#2518](https://github.com/agentscope-ai/agentscope-java/pull/2518), [#551](https://github.com/agentscope-ai/agentscope-java/issues/551))

**Spring Boot Starters**

- Add Ollama Spring Boot Starter ([#2176](https://github.com/agentscope-ai/agentscope-java/pull/2176), [#2172](https://github.com/agentscope-ai/agentscope-java/issues/2172))

### Refactored

- Change Toolkit default execution mode to parallel and improve related docs ([#2558](https://github.com/agentscope-ai/agentscope-java/pull/2558), follow-up of [#2529](https://github.com/agentscope-ai/agentscope-java/pull/2529))
- Abstract session metadata storage to decouple builders from concrete store implementations ([#2258](https://github.com/agentscope-ai/agentscope-java/pull/2258), [#2068](https://github.com/agentscope-ai/agentscope-java/issues/2068))
- Rebase Kubernetes sandbox store on [agent-sandbox](https://github.com/kubernetes-sigs/agent-sandbox) CRDs / controllers, with the cluster owning sandbox lifecycle and warm pools ([#2308](https://github.com/agentscope-ai/agentscope-java/pull/2308))

### Fixed

**Core / Agent**

- Prevent pending recovery from consuming HITL approvals ([#2109](https://github.com/agentscope-ai/agentscope-java/pull/2109), [#2534](https://github.com/agentscope-ai/agentscope-java/issues/2534))
- Apply transformed `onModelCall` text deltas to the final message so native structured-output parsing does not see stale text ([#2469](https://github.com/agentscope-ai/agentscope-java/pull/2469), [#2385](https://github.com/agentscope-ai/agentscope-java/issues/2385))
- Repair null streaming tool args from complete raw JSON ([#2451](https://github.com/agentscope-ai/agentscope-java/pull/2451), [#768](https://github.com/agentscope-ai/agentscope-java/issues/768))
- Unbind state-saver on `ReActAgent.close()` to prevent graceful-shutdown registry growth / OOM ([#2322](https://github.com/agentscope-ai/agentscope-java/pull/2322), [#2321](https://github.com/agentscope-ai/agentscope-java/issues/2321))
- Unbind `ShutdownStateSaver` on `ReActAgent.close()` to fix a memory leak ([#2384](https://github.com/agentscope-ai/agentscope-java/pull/2384))
- Mark user interrupts with interrupted reason ([#2260](https://github.com/agentscope-ai/agentscope-java/pull/2260))
- Handle malformed Unicode when writing agent state files (`UnmappableCharacterException`) ([#2255](https://github.com/agentscope-ai/agentscope-java/pull/2255), [#2204](https://github.com/agentscope-ai/agentscope-java/issues/2204))
- Forward reasoning middleware events (e.g. `InboxMiddleware` `HintBlockEvent`) to `streamEvents()` ([#2179](https://github.com/agentscope-ai/agentscope-java/pull/2179), [#2160](https://github.com/agentscope-ai/agentscope-java/issues/2160))
- Mark `ToolResultBlock.error` as a structured error ([#2174](https://github.com/agentscope-ai/agentscope-java/pull/2174), [#2157](https://github.com/agentscope-ai/agentscope-java/issues/2157), [#2111](https://github.com/agentscope-ai/agentscope-java/issues/2111))

**Model Providers**

- DashScope: route `qwen3.8-max` to the multimodal endpoint ([#2553](https://github.com/agentscope-ai/agentscope-java/pull/2553))
- DashScope: preserve SSE error response body so callers can read `request_id` ([#2278](https://github.com/agentscope-ai/agentscope-java/pull/2278), [#2197](https://github.com/agentscope-ai/agentscope-java/issues/2197))
- OpenAI: wrap streaming branch in `Flux.defer` so retries re-issue HTTP requests ([#2079](https://github.com/agentscope-ai/agentscope-java/pull/2079))
- OpenAI: terminate stream on `[DONE]` sentinel ([#2104](https://github.com/agentscope-ai/agentscope-java/pull/2104))
- OpenAI: drop non-chunk summary event messages to avoid content duplication ([#2367](https://github.com/agentscope-ai/agentscope-java/pull/2367))
- OpenAI: sanitize `name` field in `OpenAIMessageConverter` ([#2346](https://github.com/agentscope-ai/agentscope-java/pull/2346))
- OpenAI AutoConfiguration: make api-key optional ([#2175](https://github.com/agentscope-ai/agentscope-java/pull/2175))
- DeepSeek formatter: preserve `system` role ([#2189](https://github.com/agentscope-ai/agentscope-java/pull/2189), [#2168](https://github.com/agentscope-ai/agentscope-java/issues/2168))
- Ollama: honor `stream` flag in `OllamaChatModel` ([#2415](https://github.com/agentscope-ai/agentscope-java/pull/2415))
- Anthropic: map `ToolChoice.None` to disable tools (previously incorrectly forced tool use) ([#2232](https://github.com/agentscope-ai/agentscope-java/pull/2232), [#2221](https://github.com/agentscope-ai/agentscope-java/issues/2221))
- Model provider optimizations and compatibility tweaks ([#2474](https://github.com/agentscope-ai/agentscope-java/pull/2474))

**Harness / Tools / Sandbox**

- Stamp `taskId` on remote subagent forwarded events ([#2575](https://github.com/agentscope-ai/agentscope-java/pull/2575))
- Gate memory prompt guidance on disable flags ([#2565](https://github.com/agentscope-ai/agentscope-java/pull/2565))
- Emit subagent end before parent completion to avoid dropped events ([#2544](https://github.com/agentscope-ai/agentscope-java/pull/2544))
- Close subagent event stream when the parent is cancelled ([#2481](https://github.com/agentscope-ai/agentscope-java/pull/2481), [#2480](https://github.com/agentscope-ai/agentscope-java/issues/2480))
- Enforce parent DENY rules for spawned subagents ([#2477](https://github.com/agentscope-ai/agentscope-java/pull/2477))
- Preserve `RuntimeContext` during skill promotion ([#2465](https://github.com/agentscope-ai/agentscope-java/pull/2465))
- Enforce Plan Mode for subagents ([#2377](https://github.com/agentscope-ai/agentscope-java/pull/2377))
- Reject workspace path traversal (e.g. `../`) ([#2358](https://github.com/agentscope-ai/agentscope-java/pull/2358))
- Support Windows local shell execution (working-directory commands and charset decoding) ([#2304](https://github.com/agentscope-ai/agentscope-java/pull/2304), [#2268](https://github.com/agentscope-ai/agentscope-java/issues/2268))
- Isolate static subagent registries by runtime context to prevent multi-tenant crosstalk ([#2371](https://github.com/agentscope-ai/agentscope-java/pull/2371), [#2328](https://github.com/agentscope-ai/agentscope-java/issues/2328))
- Retain prior summaries in chained compaction to preserve user intent ([#2360](https://github.com/agentscope-ai/agentscope-java/pull/2360))
- Preserve skill isolation and tool result history ([#2319](https://github.com/agentscope-ai/agentscope-java/pull/2319))
- `RemoteFilesystem` recursive glob matches files at the search root ([#2343](https://github.com/agentscope-ai/agentscope-java/pull/2343))
- Mark optional FilesystemTool params as `required=false` ([#2227](https://github.com/agentscope-ai/agentscope-java/pull/2227))
- Optimize shell-execute `working_directory` parameter and tool usage hints ([#2107](https://github.com/agentscope-ai/agentscope-java/pull/2107))
- Declared subagents inherit parent `modelExecutionConfig` / `toolExecutionConfig` ([#2252](https://github.com/agentscope-ai/agentscope-java/pull/2252))
- Correct `sessionId` parameter description ([#2195](https://github.com/agentscope-ai/agentscope-java/pull/2195))

**Storage / Transport**

- PostgreSQL BaseStore schema support ([#2273](https://github.com/agentscope-ai/agentscope-java/pull/2273), [#2192](https://github.com/agentscope-ai/agentscope-java/issues/2192))
- Fix PostgreSQL upsert SQL syntax error ([#2167](https://github.com/agentscope-ai/agentscope-java/pull/2167), [#2166](https://github.com/agentscope-ai/agentscope-java/issues/2166))
- Fix `JdkHttpTransport` SSE stream being cut by absolute timeouts ([#1322](https://github.com/agentscope-ai/agentscope-java/pull/1322), [#1302](https://github.com/agentscope-ai/agentscope-java/issues/1302))

**Spring Boot / Examples**

- Fix Spring Boot starter package name ([#2264](https://github.com/agentscope-ai/agentscope-java/pull/2264))
- Use raw DashScope model names in examples (drop invalid `dashscope:` prefix) ([#2318](https://github.com/agentscope-ai/agentscope-java/pull/2318))
- Correct DashScope model name in `RuntimeContextExample` ([#2228](https://github.com/agentscope-ai/agentscope-java/pull/2228), [#2229](https://github.com/agentscope-ai/agentscope-java/issues/2229))
- Correct skill example resource path ([#2250](https://github.com/agentscope-ai/agentscope-java/pull/2250))
- Improve docs and examples ([#2508](https://github.com/agentscope-ai/agentscope-java/pull/2508))

### Documentation

- Add Agent Evolution to the Java 2.0 feature list in README ([#2494](https://github.com/agentscope-ai/agentscope-java/pull/2494))
- Clarify that the all-in-one dependency includes model providers ([#2425](https://github.com/agentscope-ai/agentscope-java/pull/2425), [#840](https://github.com/agentscope-ai/agentscope-java/issues/840))
- Fix documentation link redirects ([#2203](https://github.com/agentscope-ai/agentscope-java/pull/2203), [#2198](https://github.com/agentscope-ai/agentscope-java/issues/2198))
- Generate version-scoped `llms.txt` artifacts (`/v1`, `/v2`) ([#2188](https://github.com/agentscope-ai/agentscope-java/pull/2188), [#2185](https://github.com/agentscope-ai/agentscope-java/issues/2185))
- Document model builder customizers ([#2092](https://github.com/agentscope-ai/agentscope-java/pull/2092))
- Update model documentation ([#2100](https://github.com/agentscope-ai/agentscope-java/pull/2100))
- Correct README doc links and release notes URLs ([#2099](https://github.com/agentscope-ai/agentscope-java/pull/2099))
- Update AG-UI documentation ([#2274](https://github.com/agentscope-ai/agentscope-java/pull/2274))

---

## 2.0.0 (GA)

> Released: 2026-07-10

AgentScope Java 2.0.0 is now Generally Available. This is the first production-ready release of the 2.0 line, marking a milestone in AgentScope Java's evolution from "transparent development" to "system engineering."

**Quick links:** [Quickstart](/v2/en/docs/quickstart) | [V1 Migration Guide](/v2/en/docs/change-log) | [Going to Production](/v2/en/docs/others/going-to-production)

### 2.0 Core Design Overview

AgentScope Java 2.0 is a systematic upgrade centered on one goal: **enabling agents to reliably complete tasks**. Here is an overview of its core design:

**Dual-Layer Agent Architecture**

- **ReActAgent**: A stateless reasoning core providing the "reason → tool call → respond" ReAct loop. In 2.0, agent instances are fully stateless — all per-call mutable state is propagated via Reactor Context, allowing a single instance to safely serve multiple `(userId, sessionId)` combinations concurrently
- **HarnessAgent**: Extends ReActAgent through Middleware and Toolkit channels, adding workspace, memory, sandbox, subagents, skills, and plan mode as engineering infrastructure — the core reasoning loop is preserved, only augmented

**Message & Event Stream**

A unified ContentBlock message model (TextBlock / DataBlock / ToolUseBlock / ToolResultBlock / HintBlock, etc.) paired with `streamEvents()` emitting 28 typed AgentEvent types, making agent execution observable, interactive, and interruptible. Front-end UIs can follow text deltas, tool calls, user confirmations, and other lifecycle events in real time

**Permission System**

A new PermissionEngine establishes a three-state decision mechanism for tool calls: allow / require user approval / deny. Decisions are based on static rules, tool type, and input content analysis. Sensitive operations automatically enter a HITL approval flow

**Middleware Extension Mechanism**

A six-stage onion, pipeline, and notification model (`onAgent` / `onReasoning` / `onActing` / `onModelCall` / `onSystemPrompt` / `onAgentStateReady`), providing flexible extension points for logging, tracing, security checks, business policies, and context injection while keeping the core framework stable

**Context Engineering**

Structured compaction preserves task objectives, current state, key findings, and next steps. Oversized tool results are automatically offloaded to disk with only placeholders in the context. File tools enforce a "read before edit" policy with built-in caching to reduce redundant IO

**Workspace Abstraction**

Decouples "what the agent does" from "where it executes." Local filesystem, Docker, Kubernetes, and E2B cloud sandbox backends are unified behind a single interface. A built-in warm-up pool supports parallel RL rollout scenarios

**Model Fault Tolerance**

A unified Credential + ModelRegistry abstraction covering Qwen / OpenAI / Anthropic / Gemini / DeepSeek / Ollama. Configurable max retries and fallback model — automatic failover when the primary model is unavailable

**Enterprise Distributed Deployment**

One-line `DistributedBackend` configuration (Redis / OSS / MySQL / PostgreSQL / COS). `AgentStateStore` auto-partitions by `(userId, sessionId)`. Cross-replica session recovery, sandbox state snapshots, and subagent cross-replica routing

**Protocol Interoperability**

Built-in A2A (Agent-to-Agent) and MCP (Model Context Protocol) support, plus AG-UI protocol adaptation, covering standardized inter-agent communication and front-end rendering needs

**Multi-Agent Orchestration**

Declarative subagent specs (YAML / Markdown), runtime `agent_spawn` / `agent_send` with synchronous blocking and background delegation modes. Subagent event streams can be forwarded to the parent's `streamEvents()` in real time

**Skill System**

Four-layer skill composition (Classpath / FileSystem / Nacos / Marketplace) + SkillFilter fine-grained filtering + self-learning closed loop (propose → curate → promote)

---

### Changes Since RC5

The following are incremental changes between 2.0.0-RC5 (2026-07-07) and the GA release.

#### Added

- Fire `AllToolsDeniedEvent` hook when HITL denies all tool calls, enabling application-level handling of full-denial scenarios ([#2083](https://github.com/agentscope-ai/agentscope-java/pull/2083))
- Add guardrails for `wait_async_results` to prevent repeated long blocking waits ([#2093](https://github.com/agentscope-ai/agentscope-java/pull/2093))
- Add `PostgresDistributedStore` for PostgreSQL-backed distributed HarnessAgent state ([#2054](https://github.com/agentscope-ai/agentscope-java/pull/2054))
- Add builder customizers for OpenAI, DashScope, and Anthropic models in Spring Boot starters ([#2045](https://github.com/agentscope-ai/agentscope-java/pull/2045))

#### Fixed

**Core / Agent**

- Make `seedSystemMsg` reactive to avoid `block()` on NIO threads ([#2086](https://github.com/agentscope-ai/agentscope-java/pull/2086))
- Include ASKING ToolUseBlocks in PERMISSION_ASKING result message ([#2082](https://github.com/agentscope-ai/agentscope-java/pull/2082))
- Activate SkillToolGroup via `activateOnSkill` field ([#2057](https://github.com/agentscope-ai/agentscope-java/pull/2057))
- Save agent state on user interrupt to prevent session loss ([#1970](https://github.com/agentscope-ai/agentscope-java/pull/1970))

**Model Providers**

- Anthropic: split parallel tool calls into alternating messages to comply with API requirements ([#2090](https://github.com/agentscope-ai/agentscope-java/pull/2090))
- OpenAI: make `nativeStructuredOutput` configurable ([#2069](https://github.com/agentscope-ai/agentscope-java/pull/2069))

**Harness / Tools / Sandbox**

- External tool execution now correctly produces a suspended result ([#2071](https://github.com/agentscope-ai/agentscope-java/pull/2071))
- Allow SkillLoadTool in Plan Mode by promoting `isReadOnly` to the AgentTool interface ([#2067](https://github.com/agentscope-ai/agentscope-java/pull/2067))
- Interrupt orphan subagents when AgentSpawnTool parent subscription cancels ([#2064](https://github.com/agentscope-ai/agentscope-java/pull/2064))
- Remove unnecessary ReActAgent type restriction in MemoryFlushMiddleware ([#2078](https://github.com/agentscope-ai/agentscope-java/pull/2078))
- Resolve leading `/` paths relative to workspace in ROOTED mode ([#2049](https://github.com/agentscope-ai/agentscope-java/pull/2049))
- Pre-stage marketplace skills before workspace projection ([#2059](https://github.com/agentscope-ai/agentscope-java/pull/2059))
- Treat null exit code as success in Kubernetes `hydrateWithArchive` ([#1915](https://github.com/agentscope-ai/agentscope-java/pull/1915))
- Use updated WorkspaceSpec when resuming from persisted state ([#1928](https://github.com/agentscope-ai/agentscope-java/pull/1928))
- Support nested JSON and banner prefix in AgentRun MCP response ([#1930](https://github.com/agentscope-ai/agentscope-java/pull/1930))
- Use resolved workingDir for Docker workspaceRoot ([#2033](https://github.com/agentscope-ai/agentscope-java/pull/2033))

**Channel**

- Include PeerKind in OutboundAddress to fix group message routing ([#2060](https://github.com/agentscope-ai/agentscope-java/pull/2060))

**A2A**

- Merge streaming text chunks to avoid fragmentation ([#2058](https://github.com/agentscope-ai/agentscope-java/pull/2058))

---

## 2.0.0-RC5

> Released: 2026-07-07

### Breaking Changes

- **Model provider modularization** — OpenAI, Gemini, Anthropic, DashScope, and Ollama model providers have been moved from `agentscope-core` into independent `agentscope-extensions-model-*` extension modules. Applications must add the corresponding extension dependency ([#1890](https://github.com/agentscope-ai/agentscope-java/pull/1890), [#1916](https://github.com/agentscope-ai/agentscope-java/pull/1916), [#1947](https://github.com/agentscope-ai/agentscope-java/pull/1947), [#1972](https://github.com/agentscope-ai/agentscope-java/pull/1972))

### Added

- Unified `DataBlock` support in all provider message converters (OpenAI, DashScope, Gemini, Anthropic), covering single-agent, multi-agent, and tool-result paths ([#1933](https://github.com/agentscope-ai/agentscope-java/pull/1933))
- Native structured output handling with tools — models that support structured output can enforce JSON schema constraints alongside tool calls ([#1904](https://github.com/agentscope-ai/agentscope-java/pull/1904))
- Native structured output support for DashScope models ([#1935](https://github.com/agentscope-ai/agentscope-java/pull/1935))
- `httpRequestCustomizer` support in `McpClientBuilder` for dynamic token injection (e.g. OAuth refresh) ([#1992](https://github.com/agentscope-ai/agentscope-java/pull/1992))
- Align `AguiEvent` with the AG-UI protocol spec — add missing event types ([#1862](https://github.com/agentscope-ai/agentscope-java/pull/1862))
- Optional skill allowlist filter for subagents ([#1873](https://github.com/agentscope-ai/agentscope-java/pull/1873))
- `knownSkillNames` support in `NacosSkillRepository` ([#1853](https://github.com/agentscope-ai/agentscope-java/pull/1853))
- `CosAgentStateStore`, `CosBaseStore` and `CosDistributedStore` for Tencent Cloud COS-backed state persistence ([#1857](https://github.com/agentscope-ai/agentscope-java/pull/1857))
- Expose cached prompt tokens in `ChatUsage` ([#1868](https://github.com/agentscope-ai/agentscope-java/pull/1868))

### Fixed

**Core / Agent**

- Persist agent state on user interrupt recovery ([#2008](https://github.com/agentscope-ai/agentscope-java/pull/2008))
- Wire fallback model into `ReActAgent` ([#1851](https://github.com/agentscope-ai/agentscope-java/pull/1851))
- Fix `ReActAgent` stream event block end ordering ([#1829](https://github.com/agentscope-ai/agentscope-java/pull/1829))
- Update `ToolResultBlock` state before adding to agent context ([#1886](https://github.com/agentscope-ai/agentscope-java/pull/1886))
- Reuse classpath skill JAR file systems to avoid resource leaks ([#1981](https://github.com/agentscope-ai/agentscope-java/pull/1981))
- Resolve `serializeOnKey` gate leak in `Flux.create` callbacks ([#1796](https://github.com/agentscope-ai/agentscope-java/pull/1796))

**Model Providers**

- Map `thinkingBudget` to OpenAI-compatible API request ([#2028](https://github.com/agentscope-ai/agentscope-java/pull/2028))
- Fix Anthropic stream thinking event handling ([#1943](https://github.com/agentscope-ai/agentscope-java/pull/1943))
- Preserve `executionConfig` in `OllamaOptions` `fromOptions`/`toBuilder` ([#2011](https://github.com/agentscope-ai/agentscope-java/pull/2011))
- Degrade forced tool choice in DashScope thinking mode ([#1882](https://github.com/agentscope-ai/agentscope-java/pull/1882))

**Harness / Sandbox**

- Restore remote snapshot state deserialization — re-inject `RemoteSnapshotClient` after Jackson round-trip ([#2013](https://github.com/agentscope-ai/agentscope-java/pull/2013))
- Fix THROTTLED memory save mode losing state when recreating instances per request ([#1788](https://github.com/agentscope-ai/agentscope-java/pull/1788))
- Propagate `userId` through wakeup dispatch ([#2001](https://github.com/agentscope-ai/agentscope-java/pull/2001))
- Run message bus heartbeat on `boundedElastic` instead of `parallel` scheduler ([#1974](https://github.com/agentscope-ai/agentscope-java/pull/1974))
- Avoid duplicating `GracefulShutdownMiddleware` in `fromAgent` ([#1952](https://github.com/agentscope-ai/agentscope-java/pull/1952))
- Escape spaces in skill paths returned by `ShellPathPolicy` ([#2031](https://github.com/agentscope-ai/agentscope-java/pull/2031))
- Fallback to simple key-value extraction when YAML parsing fails ([#2027](https://github.com/agentscope-ai/agentscope-java/pull/2027))
- Report sandbox file sizes in `ls` ([#1838](https://github.com/agentscope-ai/agentscope-java/pull/1838))
- Normalize Windows `list_files` paths ([#1892](https://github.com/agentscope-ai/agentscope-java/pull/1892))
- Normalize `\r\n` to `\n` for file content in `LocalFilesystem.edit()` ([#2020](https://github.com/agentscope-ai/agentscope-java/pull/2020))
- Treat `"."` as root equivalent in `CompositeFilesystem` ([#1830](https://github.com/agentscope-ai/agentscope-java/pull/1830))
- Validate `working_directory` to prevent namespace escape ([#1834](https://github.com/agentscope-ai/agentscope-java/pull/1834))
- Fall back to `LocalFilesystemSpec` when no distributed `AgentStateStore` is configured ([#1841](https://github.com/agentscope-ai/agentscope-java/pull/1841))
- Fix WebSocket race in Kubernetes `hydrateWithArchive` causing `exit=null` ([#1903](https://github.com/agentscope-ai/agentscope-java/pull/1903))
- Tolerate wrapped sandbox base64 downloads ([#1866](https://github.com/agentscope-ai/agentscope-java/pull/1866))
- Remove `AgentRun` sandbox API version prefix ([#1891](https://github.com/agentscope-ai/agentscope-java/pull/1891))
- Add connect JSON codec support for E2B sandbox ([#1844](https://github.com/agentscope-ai/agentscope-java/pull/1844))

**Tracing / Observability**

- Fix orphan spans in `OtelTracingMiddleware` by reading parent OTel Context from Reactor `ContextView` ([#1940](https://github.com/agentscope-ai/agentscope-java/pull/1940))
- Fix child spans not seeing correct parent spans in `OtelTracingMiddleware` ([#1909](https://github.com/agentscope-ai/agentscope-java/pull/1909))
- Propagate Reactor context to chunk event hooks ([#1923](https://github.com/agentscope-ai/agentscope-java/pull/1923))

**Subagent**

- Propagate parent `RuntimeContext` to child agents ([#1833](https://github.com/agentscope-ai/agentscope-java/pull/1833))
- Propagate parent middleware to subagents ([#1843](https://github.com/agentscope-ai/agentscope-java/pull/1843))

**A2A**

- Handle streaming backpressure ([#1734](https://github.com/agentscope-ai/agentscope-java/pull/1734))
- Preserve AgentScope message roles across A2A conversion ([#1995](https://github.com/agentscope-ai/agentscope-java/pull/1995))

**AG-UI**

- Propagate run input and frontend tools ([#1895](https://github.com/agentscope-ai/agentscope-java/pull/1895))

**Other**

- Wrap middleware `doFlush` in `Mono.defer` to prevent premature evaluation ([#1880](https://github.com/agentscope-ai/agentscope-java/pull/1880))
- Nacos auto-configurations should be opt-in (`matchIfMissing=false`) and fix A2A server-addr override ([#1709](https://github.com/agentscope-ai/agentscope-java/pull/1709))
- Add `ObjectMapper` bean for `MarketContributionService` in DataAgent ([#1993](https://github.com/agentscope-ai/agentscope-java/pull/1993))

### Documentation

- Clarify stream event `blockId` semantics ([#2016](https://github.com/agentscope-ai/agentscope-java/pull/2016))
- Improve model provider documentation ([#1986](https://github.com/agentscope-ai/agentscope-java/pull/1986))
- Remove invalid `ChatResponse.isLast` references ([#1921](https://github.com/agentscope-ai/agentscope-java/pull/1921))
- Fix multi-replica Redis example — declare jedis dependency and add `stateStore` ([#1869](https://github.com/agentscope-ai/agentscope-java/pull/1869))
- Fix `MemoryCompactionExample` to show memory files and fire compaction ([#1978](https://github.com/agentscope-ai/agentscope-java/pull/1978))

---

## 2.0.0-RC4

> Released: 2026-06-18

### Added

- Agent harness now supports async tool execution and notifications, including message bus, async tool registry, and scheduled wakeup dispatching ([#1802](https://github.com/agentscope-ai/agentscope-java/pull/1802))
- String/Message convenience overloads for agent calls; all formatters now support `HintBlock` ([#1802](https://github.com/agentscope-ai/agentscope-java/pull/1802))
- Persistent spawn registry in tool context state enables subagent cross-replica routing and session recovery ([#1817](https://github.com/agentscope-ai/agentscope-java/pull/1817))
- `DynamicSkillMiddleware` implements `ToolkitAware` to receive the resolved toolkit dynamically ([#1828](https://github.com/agentscope-ai/agentscope-java/pull/1828))
- Kubernetes sandbox now supports injecting environment variables into pods ([#1789](https://github.com/agentscope-ai/agentscope-java/pull/1789))

### Fixed

- Fixed SIGKILL race condition in Kubernetes file uploads by using two-phase archive strategy ([#1826](https://github.com/agentscope-ai/agentscope-java/pull/1826))
- Fixed resource leak where timed-out sub-agents were not interrupted on retry ([#1784](https://github.com/agentscope-ai/agentscope-java/pull/1784))
- Fixed typed attributes being lost when copying `RuntimeContext` ([#1813](https://github.com/agentscope-ai/agentscope-java/pull/1813))
- Fixed `JdbcStore` table initialization failure under MySQL utf8mb4 charset ([#1781](https://github.com/agentscope-ai/agentscope-java/pull/1781))
- Made session JSONL offload idempotent to prevent duplicate writes ([#1774](https://github.com/agentscope-ai/agentscope-java/pull/1774))
- Fixed OpenTelemetry context propagation in `TelemetryTracer` ([#1799](https://github.com/agentscope-ai/agentscope-java/pull/1799))
- Fixed NPE in `OllamaChatModel` when options are null during tool choice retrieval ([#1803](https://github.com/agentscope-ai/agentscope-java/pull/1803))
- Added missing Jackson annotations to `LocalSandboxSnapshot` for proper serialization ([#1825](https://github.com/agentscope-ai/agentscope-java/pull/1825))
- Fixed sandbox glob not supporting `**/` recursive patterns ([#1684](https://github.com/agentscope-ai/agentscope-java/pull/1684))
- Fixed `SkillFilter` matching using composite ID instead of skill name ([#1771](https://github.com/agentscope-ai/agentscope-java/pull/1771))
- Allow custom default vision model in `MultiModalTool` ([#1701](https://github.com/agentscope-ai/agentscope-java/pull/1701))

### Documentation

- Fixed incorrect hook signatures in middleware docs ([#1835](https://github.com/agentscope-ai/agentscope-java/pull/1835))
- Fixed references to non-existent `.sandboxContext()` in doc examples ([#1792](https://github.com/agentscope-ai/agentscope-java/pull/1792))
- Fixed `getToolName()` → `getToolCallName()` in v2 docs ([#1760](https://github.com/agentscope-ai/agentscope-java/pull/1760))
- Added AI context menu to documentation site

---

## 2.0.0-RC3

> Released: 2026-06-11

### Added

- **`AgentResultEvent`** — new event type emitted when an agent finishes processing, immediately before `AgentEndEvent`, carrying the final `Msg` result. Consumers of `streamEvents()` can obtain the result directly from the event stream without separately subscribing to the `Mono<Msg>` return value
- **`CustomEvent`** — generic extensible event for middleware to push application-level notifications (state changes, team updates, etc.) to front-end subscribers without adding per-use-case `AgentEventType` entries. Built-in well-known names: `state_updated`, `team_updated`
- **`HintBlockEvent`** — one-shot hint block event for delivering complete content such as team messages, background tool results, and user interruptions, as opposed to streamed text/thinking blocks
- **`WorkspacePathNormalizer`** — file path normalization utility that converts absolute paths to workspace-relative form. Registers prefixes based on the active filesystem mode (local / sandbox) to prevent cross-mode prefix collisions
- **`toolCallName` on tool events** — `ToolCallDeltaEvent`, `ToolCallEndEvent`, `ToolResultDataDeltaEvent`, `ToolResultEndEvent`, and `ToolResultTextDeltaEvent` now carry a `toolCallName` field, so consumers no longer need to cache the name mapping from the start event

### Changed

- **Unified `call()` / `streamEvents()` core** — introduced an internal `buildAgentStream` method as the shared implementation for both `call()` and `streamEvents()`, ensuring the `onAgent` middleware chain fires consistently on all invocation paths. `call()` now extracts the result from `AgentResultEvent` in the event stream; the legacy standalone `agentImpl` logic has been removed
- **Session state always reloaded from store in distributed deployments** — when an `AgentStateStore` is configured, `activateSlotForContext` now reloads the agent state and permission engine from the store at the start of every call, preventing stale local cache reads when the same sessionId drifts across machines
- **`ToolResultEvictionMiddleware` timing fix** — moved from `onActing` (where state had not yet been written, making eviction a no-op) to `onReasoning`, ensuring tool results are persisted before eviction runs
- **Simplified `LocalFilesystem` path resolution** — refactored path resolution logic to reduce redundant code

### Fixed

- Fixed `RuntimeContext` not setting `userId` in tests, causing inaccurate user isolation

---

## 2.0.0-RC2

> Released: 2026-06-09

### Added

- **`projectWritable` mode** (`LocalFilesystemSpec`) — when enabled, the agent's file writes are routed by path: workspace metadata (`MEMORY.md`, `agents/`, `skills/`, etc.) goes to workspace; everything else (code, configs) lands in the project directory. Designed for code-generation agents. See [Filesystem · Project-writable mode](/v2/en/docs/harness/filesystem#project-writable-mode-projectwritable)
- **Runtime permission mode switching** — new `HarnessAgent.setPermissionMode()` / `getPermissionMode()` for dynamically adjusting the permission mode per session at runtime
- **Subagent event stream forwarding** — `streamEvents()` now forwards child agent intermediate events (`TextBlockDelta`, `ToolCallStart`, etc.) in real time, each carrying a `source` path identifying the originating agent
- **`AgentEvent.source` field** — all `AgentEvent` instances now carry a `source` field to distinguish main agent events (`source = null`) from sub agent events (`source = "main/researcher"` path format) within the same event stream, enabling consumer-side demuxing without extra state
- **Custom prompt and model for Compaction / Memory** — `CompactionConfig` and `MemoryConfig` gain `.model()` and `.prompt()` builder methods, allowing a dedicated lightweight model and custom prompt for context compaction and memory extraction instead of the agent's primary model
- **Qwen 3.7 model support** — `ModelRegistry` now resolves `dashscope:qwen3.7-plus` and other Qwen 3.7 series models
- **Direct subagent messaging** — `agent_send` lets callers send messages directly to a declared subagent and receive its response without going through the parent agent's reasoning loop
- **Channel module** — new `agentscope-extensions-channel` module family for IM platform integration (DingTalk, Feishu/Lark, WeCom, GitHub, GitLab), with a built-in ChatUI for an out-of-the-box conversational interface
- **`DistributedBackend` unified interface** — new `DistributedBackend` abstraction that consolidates all distributed storage components (`AgentStateStore`, `BaseStore`, `SandboxSnapshotSpec`) into a single configuration point. Built-in implementations include `RedisDistributedBackend`, `OssDistributedBackend`, and `MysqlDistributedBackend`. One call to `HarnessAgent.builder().distributedBackend(backend)` wires up the entire distributed backend — no more separate stateStore, baseStore, and snapshotSpec configuration

### Changed

- **Agent fully stateless** — `ReActAgent` no longer holds any mutable per-session state; all mutable state is encapsulated in an internal `CallExecution` and propagated via Reactor Context. A single agent instance can safely serve multiple `(userId, sessionId)` combinations concurrently
- **Session interface replaced by `AgentStateStore`** — removed `SessionManager`, `StatePersistence`, and related legacy interfaces; unified on `AgentStateStore` (built-in: `InMemoryAgentStateStore`, `JsonFileAgentStateStore`, `RedisAgentStateStore`, `MysqlAgentStateStore`), auto-partitioned by `(userId, sessionId)`
- **`BaseStore` interface package renamed** — `BaseStore` and related interfaces moved to a new package; code using the old import path needs updating
- **Extension module coordinates consolidated** — several extension Maven coordinates have been reorganized by capability. For example, `agentscope-extensions-session-redis` is now `agentscope-extensions-redis` (bundling `RedisAgentStateStore`, `RedisStore`, `RedisSnapshotSpec`, etc.). Update `<artifactId>` in your pom if you were using the old coordinates
- **Sandbox implementations extracted from harness core** — Docker, Kubernetes, E2B, Daytona, AgentRun sandbox backends have been moved out of `agentscope-harness` into standalone extension modules (`agentscope-extensions-sandbox-*`). The harness core retains only the abstract interfaces (`SandboxFilesystemSpec`, etc.) and no longer transitively pulls in any concrete sandbox dependency. Add the corresponding extension explicitly if you need sandbox support, e.g. `agentscope-extensions-sandbox-docker` for Docker
- **Plan Mode improvements** — improved plan file persistence and recovery, smoother `plan_enter` / `plan_write` / `plan_exit` tool-chain interaction, more robust HITL approval flow
- **Skill self-evolution enhancements** — refined the propose (`ProposeSkillTool`) → curate (`SkillCurator`) → promote (`SkillPromoter`) closed loop, improved skill matching accuracy and cross-session reuse
- `DashScopeHttpClient` request timeout and retry policy adjustments
- `ModelRegistry` model resolution logic improvements
- `AgentState` serialization format updates

### Fixed

- Fixed `PermissionContextState` losing state during cross-session restoration
- Fixed `agentscope-all` missing 4 sandbox extension modules (`sandbox-kubernetes`, `sandbox-agentrun`, `sandbox-daytona`, `sandbox-e2b`)

---

## 2.0.0-RC1

> Released: 2025-05-28

First 2.0 Release Candidate. Contains the full architectural upgrade from 1.x:

- Harness engineering (workspace, memory, skills, subagents, Plan Mode, context compaction)
- Enterprise-grade distributed deployment (multi-tenant isolation, sandbox execution, permission system, session recovery)
- Core framework redesign (event stream, message model, Middleware, HITL)

For the complete 1.x → 2.0 change list, see the [V1 Migration Guide](/v2/en/docs/change-log).
