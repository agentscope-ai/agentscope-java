# GitHub Issues Analysis Report — 2026-06-18

Analysis of all 50 open issues at https://github.com/agentscope-ai/agentscope-java/issues against the current `main` branch code.

## Summary

| Category | Count |    
|----------|-------|
| Confirmed bugs (PR created) | 8 (across 5 PRs) |
| Confirmed bugs (needs further work) | 4 |
| Already fixed on main | 3 |
| Not a bug / user confusion | 16 |
| Feature requests | 12 |
| Questions | 3 |
| Empty / insufficient info | 4 |

## PRs Created

| PR | Issues Fixed | Title |
|----|-------------|-------|
| [#1830](https://github.com/agentscope-ai/agentscope-java/pull/1830) | #1737 | fix(filesystem): treat "." as root equivalent in CompositeFilesystem |
| [#1831](https://github.com/agentscope-ai/agentscope-java/pull/1831) | #1707 | fix(nacos): change matchIfMissing to false in auto-configurations |
| [#1832](https://github.com/agentscope-ai/agentscope-java/pull/1832) | #1742, #1720 | fix(model): delegate supportsNativeStructuredOutput to formatter |
| [#1833](https://github.com/agentscope-ai/agentscope-java/pull/1833) | #1739, #1731 | fix(subagent): propagate parent RuntimeContext to child agents |
| [#1834](https://github.com/agentscope-ai/agentscope-java/pull/1834) | #1777 | fix(shell): validate working_directory to prevent namespace escape |

---

## Detailed Analysis by Issue

### Core / Model

#### #1770 — OllamaChatModel NPE on null GenerateOptions
- **Status**: NOT_A_BUG
- **Evidence**: `OllamaChatModel.doStream()` already has null guard: `options != null ? options.getToolChoice() : null`. `OllamaOptions.fromGenerateOptions(null)` also handles null safely. The reporter may have been on an older version.

#### #1742 — OpenAIChatModel supportsNativeStructuredOutput() hardcoded true
- **Status**: CONFIRMED_BUG — **PR #1832**
- **Priority**: HIGH
- **Root cause**: `OpenAIChatModel.supportsNativeStructuredOutput()` returns `true` unconditionally, but DeepSeek/vLLM/Ollama/GLM don't support `response_format` with `json_schema`.
- **Fix**: Added `supportsNativeStructuredOutput()` to `OpenAIChatFormatter` and overrode to `false` in `DeepSeekFormatter`, `GLMFormatter`, and their multi-agent variants.

#### #1720 — deepseek-v4-flash structured output exception
- **Status**: CONFIRMED_BUG (duplicate of #1742) — **PR #1832**

#### #1782 — Multimodal messages (DataBlock) not recognized by model
- **Status**: CONFIRMED_BUG (needs further work)
- **Priority**: HIGH
- **Root cause**: `DataBlock` is not handled by any formatter. `AbstractBaseFormatter.hasMediaContent()` only checks `ImageBlock`/`AudioBlock`/`VideoBlock`, and no formatter's `convertContentBlocks()` has a `DataBlock` branch. `DataBlock` content is silently dropped.
- **Recommendation**: Add `DataBlock` handling to `AbstractBaseFormatter.hasMediaContent()` and each formatter's content converter, routing by `Source.getMediaType()`.

#### #1785 — ChatResponse isLast method removed
- **Status**: NOT_A_BUG
- **Evidence**: `isLast()` was never on `ChatResponse`; it exists on `Event` (line 178-180). User confusion.

#### #1797 — Model returns DSML tags
- **Status**: NOT_A_BUG
- **Evidence**: Zero references to "DSML" in the codebase. This is DeepSeek model behavior, not a framework issue.

### Core / Agent

#### #1798 — serializeOnKey gate leak when middleware throws
- **Status**: NOT_A_BUG
- **Evidence**: `AgentBase.serializeOnKey()` uses `doFinally()` which fires on all terminal signals (complete, error, cancel). Gate is always released.

#### #1779 — Plan hint skipped on same-turn finish_subtask + finish_plan
- **Status**: NOT_A_BUG (code not in repo)
- **Evidence**: `DefaultPlanToHint`, `PlanNotebook.finishSubtask/finishPlan` do not exist in agentscope-java. The issue likely references the Python AgentScope or an unreleased feature.

#### #1741 — ReActAgent.interrupt() can't close SSE
- **Status**: NOT_A_BUG (API misuse)
- **Evidence**: The no-arg `interrupt()` is deprecated. Users should use `interrupt(RuntimeContext)` with the same context as `streamEvents()`.

#### #1739 — SubAgent loses parent Agent's Session
- **Status**: CONFIRMED_BUG — **PR #1833**
- **Priority**: HIGH
- **Root cause**: `DefaultAgentManager.invokeAgent()` builds a fresh `RuntimeContext` with only `sessionId`/`userId`, discarding parent extras.

#### #1731 — Harness subagent loses RuntimeContext
- **Status**: CONFIRMED_BUG (same root cause as #1739) — **PR #1833**
- **Priority**: HIGH
- **Note**: Commit 45abd288 fixed `RuntimeContext.Builder.from()` copy semantics, but `invokeAgent()` never called `builder(parentRc)`.

#### #1715 — ReactAgent/HarnessAgent skill activation fails
- **Status**: NOT_A_BUG
- **Evidence**: Both `DynamicSkillMiddleware` (core) and `HarnessSkillMiddleware` (harness) work correctly. Likely a misconfiguration issue.

#### #1787 — [Feature] ReActAgent support switching Model
- **Status**: FEATURE_REQUEST (MEDIUM)
- **Recommendation**: Can be achieved today via a custom `MiddlewareBase` overriding `onModelCall`. A first-class `model` field on `GenerateOptions` or `RuntimeContext` could be added as an enhancement.

### Core / Tool & Memory

#### #1738 — skill_manage create then write_file Skill not found
- **Status**: NOT_A_BUG
- **Evidence**: `WorkspaceSkillRepository` has no in-memory cache; every `skillExists()` call performs a live `filesystem.glob()`. If reproducible, it's a filesystem-level race.

#### #1737 — CompositeFilesystem.ls() root "." vs "/"
- **Status**: CONFIRMED_BUG — **PR #1830**
- **Priority**: MEDIUM
- **Root cause**: `WorkspacePathNormalizer.tryStrip()` returns `"."` for workspace root, but `CompositeFilesystem.ls()` only checks `"/"`. Route merging was skipped for `"."`.

#### #1775 — read_file doesn't prepend userId
- **Status**: NOT_A_BUG
- **Evidence**: Both `readFile` and `writeFile` use the same `norm(path)` → `WorkspacePathNormalizer.normalize()`. Namespace isolation is at the `AbstractFilesystem` layer.

#### #1740 — compressIfNeeded() single execution
- **Status**: NOT_A_BUG
- **Evidence**: `AutoContextMemory` class does not exist in this codebase. The equivalent `ConversationCompactor.compactIfNeeded()` uses a single strategy by design.

#### #1786 — [Feature] Memory flush THROTTLED instance scope
- **Status**: FEATURE_REQUEST (LOW)
- **Evidence**: `MemoryFlushMiddleware` already supports `IsolationScope` (USER/SESSION/AGENT/GLOBAL). Cross-process persistence would require external state storage.

#### #1769 — SessionTree.flush duplicate offload
- **Status**: CONFIRMED_BUG (needs further work)
- **Priority**: MEDIUM
- **Root cause**: When `msg.getId()` returns null, `MemoryFlushManager.offloadToSessionTree()` passes null → `SessionEntry` generates random UUID → dedup fails → duplicate append on every call.
- **Recommendation**: Use `msg.getId()` when available; generate deterministic ID from content hash when null. Also add dedup guard in `SessionTree.append()`.

#### #1705 — Chinese Skill name path encoding
- **Status**: NOT_A_BUG
- **Evidence**: `SkillManageTool.validateName()` rejects non-ASCII names with regex `^[a-z0-9][a-z0-9._-]*$`. Design decision — non-ASCII names not supported.

#### #1728 — [Feature] McpClientBuilder httpRequestCustomizer
- **Status**: FEATURE_REQUEST (MEDIUM)
- **Evidence**: `McpClientBuilder` has `header()` for static headers but no per-request customizer for dynamic tokens (OAuth refresh). Valid feature gap.

### Harness / Sandbox

#### #1822 — LocalSandboxSnapshot lacks Jackson constructor
- **Status**: ALREADY_FIXED
- **Evidence**: Commit `14c04005` added `@JsonCreator` and `@JsonProperty` annotations.

#### #1777 — Shell execution can escape namespace
- **Status**: CONFIRMED_BUG — **PR #1834**
- **Priority**: HIGH (security)
- **Root cause**: `ShellExecuteTool.execute()` blindly prepended `cd <working_directory>` without validating against absolute paths or `..` traversal.

#### #1772 — Sandbox shell command syntax error
- **Status**: NOT_A_BUG (insufficient evidence)
- **Evidence**: Current code passes commands cleanly to `sh -c`. No parentheses wrapping found. May be stale or version-specific.

#### #1702 — Sandbox type-safe context lost
- **Status**: ALREADY_FIXED
- **Evidence**: Commit `45abd288` fixed `RuntimeContext.Builder.from()` to properly copy `typedAttributes`.

#### #1725 — agentscope-builder remote/sandbox mode
- **Status**: NOT_A_BUG (documentation)
- **Evidence**: Builder uses `@ConditionalOnMissingBean` with H2 fallback. Redis requires explicit dependency + bean config.

#### #1723 — builder.workspace-store.fs-spec config not implemented
- **Status**: CONFIRMED_BUG (LOW, documentation gap)
- **Evidence**: Zero references to `fs-spec`/`fsSpec` in code. Property is unimplemented.

#### #1710 — KubernetesFilesystemSpec RemoteSandboxSnapshot deserialization failure
- **Status**: CONFIRMED_BUG (needs further work)
- **Priority**: MEDIUM
- **Root cause**: `RemoteSandboxSnapshot` is listed in `@JsonSubTypes` but has no `@JsonCreator` and requires a non-serializable `RemoteSnapshotClient`. Second-call deserialization fails.
- **Recommendation**: Add serialization proxy or custom deserializer; or remove from `@JsonSubTypes`.

#### #1712 — KubernetesFilesystemSpec env injection
- **Status**: ALREADY_FIXED
- **Evidence**: Commit `fec31c05` implemented env variable injection in `Fabric8KubernetesPodRuntime`.

#### #1704 — RC2 production readiness
- **Status**: QUESTION — General discussion, not a specific bug.

### Extensions / Integration

#### #1821 — A2A streaming BufferingTube overflow
- **Status**: NOT_A_BUG
- **Evidence**: The `BufferingTube` with default 256 described in the issue doesn't exist in the current SDK. The actual implementation uses `EventQueue` with `DEFAULT_QUEUE_SIZE = 1000` and proper backpressure.

#### #1801 — MysqlSession PlanNotebook.currentPlan @JsonIgnore
- **Status**: NOT_A_BUG
- **Evidence**: `PlanNotebook` class doesn't exist. The actual `PlanModeContextState` has full `@JsonProperty` annotations and passes round-trip tests.

#### #1795 — OSS module incompatible with MinIO
- **Status**: FEATURE_REQUEST (not a bug)
- **Evidence**: Module exclusively uses Aliyun OSS SDK. MinIO requires AWS S3 SDK (V4 signature). Recommendation: create a new `agentscope-extensions-s3` module.

#### #1790 — JGit CloneCommand vs git clone directory
- **Status**: NOT_A_BUG
- **Evidence**: `GitSkillRepository` always uses explicit temp dirs or caller-supplied paths. Never relies on JGit auto-deriving directory names.

#### #1764 — AguiAgentAdapter no RuntimeContext
- **Status**: CONFIRMED_BUG (needs further work)
- **Priority**: HIGH
- **Root cause**: `AguiAgentAdapter.run()` calls the deprecated `agent.stream(msgs, options)` (no RuntimeContext). No session isolation, no user identification.
- **Recommendation**: Build `RuntimeContext` from AG-UI's `threadId` (as sessionId) + available user info; call `agent.streamEvents()` instead.

#### #1733 — agui protocol no multimodal
- **Status**: CONFIRMED_BUG (needs further work)
- **Priority**: MEDIUM
- **Root cause**: `AguiMessage.content` is plain `String`. `AguiMessageConverter` silently drops `ImageBlock`, `AudioBlock`, `VideoBlock`, `DataBlock`.
- **Recommendation**: Change content to structured list; add conversion branches for media blocks.

#### #1707 — Nacos auto-config matchIfMissing=true
- **Status**: CONFIRMED_BUG — **PR #1831**
- **Priority**: HIGH
- **Root cause**: All 5 `@ConditionalOnProperty` annotations had `matchIfMissing=true`, activating Nacos even without config.

#### #1726 — AG-UI missing metadata for permission flow
- **Status**: CONFIRMED_BUG (feature gap, MEDIUM)
- **Evidence**: No `AguiEvent` type carries metadata. No permission/confirmation event types exist. Bidirectional event support missing.

#### #1805 — [Feature] extensions-studio multi runId
- **Status**: FEATURE_REQUEST (LOW)
- **Evidence**: `StudioManager` is static singleton with one `runId`. Would need refactor to instance-based.

#### #1730 — [Feature] spring-ai-alibaba multi-agent
- **Status**: FEATURE_REQUEST — No spring-ai-alibaba integration exists.

#### #1763 — NacosSkillRepository getAllSkills returns empty
- **Status**: NOT_A_BUG (by design)
- **Evidence**: Documented as read-only in class Javadoc. Only `getSkill(name)` is supported via ZIP download.

#### #1736 — [Feature] MysqlSkillRepository sample data
- **Status**: QUESTION — Request for documentation/examples.

### Remaining Issues

#### #1766 — [Feature] onActing middleware write tool_result
- **Status**: FEATURE_REQUEST (MEDIUM) — Valid API gap for per-call selective deny.

#### #1767 — [Feature] Pod securityContext customizer
- **Status**: FEATURE_REQUEST (MEDIUM) — No security context hooks in `Fabric8KubernetesPodRuntime.createPod()`.

#### #1717 — [Feature] streamEvents structured output parameter
- **Status**: FEATURE_REQUEST

#### #1714 — [Feature] Nested agent streaming in agui
- **Status**: FEATURE_REQUEST

#### #1706 — Optimization suggestions for RC2
- **Status**: FEATURE_REQUEST — General optimization discussion.

#### #1708 — PostReasoningEvent gotoReasoning semantics
- **Status**: QUESTION
- **Evidence**: `PostReasoningEvent.gotoReasoning()` exists and is documented, but the entire `PostReasoningEvent` class is `@Deprecated(forRemoval = true, since = "2.0.0")` — migrate to `MiddlewareBase`.

#### #1809 — Cannot resolve symbol TaskToolsBuilder
- **Status**: QUESTION
- **Evidence**: No `TaskToolsBuilder` class exists anywhere in the codebase.

#### #1729 — K8s pod sandbox workspace failure
- **Status**: INSUFFICIENT_INFO — Very brief report, no reproducible details.
