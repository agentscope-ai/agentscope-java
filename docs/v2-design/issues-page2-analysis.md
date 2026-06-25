# GitHub Issues Page 2 Analysis Report

**Date**: 2026-06-18
**Scope**: 25 open issues from [issues?page=2](https://github.com/agentscope-ai/agentscope-java/issues?page=2)
**Method**: Each issue analyzed against current `main` branch code

## Summary

| Category | Count |
|----------|-------|
| Bug — fix needed | 14 |
| Bug — already fixed | 2 |
| Enhancement request | 6 |
| Question / docs needed | 2 |
| Needs more info | 1 |

---

## Detailed Analysis

### #1740 — compressIfNeeded() 单次执行，token 仍超阈值不再压缩

**Status**: Bug exists — fix needed
**Labels**: bug, area/core/memory

`ConversationCompactor.compactIfNeeded()` runs a single pass: checks `shouldCompact()`, determines cutoff, summarizes prefix, returns. No loop. If conversation is extremely large, a single summarization may still exceed `triggerTokens`. Caller `CompactionMiddleware.onReasoning()` also does not re-invoke compaction.

**Key files**:
- `agentscope-harness/.../memory/compaction/ConversationCompactor.java` (line 89)
- `agentscope-harness/.../middleware/CompactionMiddleware.java` (line 74)

**Recommendation**: Add loop in `compactIfNeeded` (with max iteration cap, e.g. 3 passes) until token count drops below threshold.

---

### #1739 — 子Agent丢失父Agent的Session

**Status**: By design, but needs clarification
**Labels**: bug, area/core/agent

`DefaultAgentManager.invokeAgent()` intentionally creates a new `RuntimeContext` with a fresh `sessionId` for the child. `SubagentFactory` javadoc explicitly says to bake parent IDs into the child's `SessionKey`. The `userId` IS propagated but parent `sessionId` is replaced.

**Key files**:
- `agentscope-harness/.../subagent/DefaultAgentManager.java` (lines 179-180)
- `agentscope-harness/.../tool/AgentSpawnTool.java` (lines 261-279)

**Recommendation**: Behavior is intentional for state isolation. If user needs parent session context propagation (not just ID), the `SubagentFactory` implementation should be configured to copy relevant context. Clarify with reporter and improve documentation.

---

### #1737 — CompositeFilesystem.ls() 根目录不显示路由条目

**Status**: Bug exists — fix needed
**Labels**: bug, area/core/tool

`WorkspacePathNormalizer.tryStrip()` returns `"."` when input equals workspace root, but `CompositeFilesystem.ls()` only merges route entries when `"/".equals(path)`. Since `"." != "/"`, route entries (skills/, memory/, knowledge/) are invisible at root.

**Key files**:
- `agentscope-harness/.../workspace/WorkspacePathNormalizer.java` (line 94-95)
- `agentscope-harness/.../filesystem/CompositeFilesystem.java` (line 191)

**Recommendation**: Change `CompositeFilesystem.ls()` to also check `".".equals(path)`, or change `tryStrip()` to return `"/"` when path equals prefix.

---

### #1736 — MysqlSkillRepository 样例数据

**Status**: Enhancement request
**Labels**: enhancement, area/ext/integration

`MysqlSkillRepository` has comprehensive javadoc with DDL and Java code examples, but no SQL INSERT sample data. Tests use programmatic data, not SQL scripts.

**Recommendation**: Add sample SQL INSERT statements in README or as `sample-data.sql`.

---

### #1733 — AG-UI 协议不支持多模态

**Status**: Enhancement needed
**Labels**: bug, area/ext/integration

`AguiMessage.content` is `String` only. `AguiMessageConverter.toAguiMessage()` only extracts `TextBlock` — `ImageBlock`, `AudioBlock`, `VideoBlock`, `DataBlock` are silently dropped. Core AgentScope supports all these content types.

**Key files**:
- `agentscope-extensions/.../agui/model/AguiMessage.java`
- `agentscope-extensions/.../agui/converter/AguiMessageConverter.java` (line 93-113)

**Recommendation**: Redesign `AguiMessage.content` as `List<AguiContentPart>` and add converter branches for non-text blocks, if AG-UI protocol spec supports it.

---

### #1731 — Harness subagent 委派丢失 RuntimeContext

**Status**: Bug exists — fix needed
**Labels**: bug, area/harness

`DefaultAgentManager.invokeAgent()` and `invokeAgentStream()` build a brand-new empty `RuntimeContext` with only `sessionId` and `userId`. String extras, typed attributes, `ToolExecutionContext`, custom metadata are all lost. Commit `45abd288` fixed a related but different problem (intra-agent context copying in `HarnessAgent.ensureSessionDefaults()`). The inter-agent delegation path remains broken.

**Key files**:
- `agentscope-harness/.../subagent/DefaultAgentManager.java` (lines 179-180, 218)
- `agentscope-harness/.../tool/AgentSpawnTool.java` (lines 355, 519, 680, 1089)

**Recommendation**: Use `RuntimeContext.builder(parentCtx).sessionId(childSessionId).build()` instead of building from scratch.

---

### #1730 — Spring AI Alibaba 多智能体在 agui-start 中使用

**Status**: Enhancement / docs needed
**Labels**: enhancement, area/ext/integration

No Spring AI Alibaba integration exists in the codebase. V1 docs reference classes and example directories that don't exist.

**Recommendation**: Either implement the integration or update documentation to clarify current state. This is a question/feature request.

---

### #1729 — K8s Pod 沙箱 workspace 问题

**Status**: Bug exists — fix needed
**Labels**: bug, area/harness

Three problems: (1) `Fabric8KubernetesPodRuntime.exec()` prepends `cd <workspaceRoot>` to every command including workspace creation itself, causing circular failure. (2) `/workspace` path hardcoded in 5 locations. (3) No persistent storage — `mkdir -p` on ephemeral filesystem.

**Key files**:
- `agentscope-extensions/.../kubernetes/Fabric8KubernetesPodRuntime.java` (line 112)
- `KubernetesSandboxState.java`, `KubernetesSandboxClientOptions.java`, `WorkspaceSpec.java`

**Recommendation**: Fix `exec()` to not prepend `cd` when creating workspace root. The persistent storage and idle timeout issues are separate enhancements.

---

### #1728 — McpClientBuilder 动态 Token 注入

**Status**: Enhancement/fix needed
**Labels**: enhancement, area/core/tool

Current code uses deprecated `customizeRequest` API (MCP SDK 0.17.0) which applies headers at build time, not per-request. MCP SDK 2.0 removes it entirely. Users cannot rotate auth tokens.

**Key files**:
- `agentscope-core/.../tool/mcp/McpClientBuilder.java` (lines 739-744, 780-785)

**Recommendation**: Replace `customizeRequest` with `httpRequestCustomizer` and expose per-request customizer in the public API.

---

### #1726 — AG-UI 协议缺少 metadata，HITL 确认流程不通

**Status**: Bug exists — fix needed
**Labels**: bug, area/ext/integration

Five gaps: (1) `AguiMessage` has no `metadata` field, (2) `AguiMessageConverter` doesn't map metadata, (3) `AguiAgentAdapter.convertEvent()` silently drops `RequireUserConfirmEvent`, (4) No confirm event types in `AguiEventType`, (5) No REST endpoint for confirm responses.

**Key files**:
- `agentscope-extensions/.../agui/model/AguiMessage.java`
- `agentscope-extensions/.../agui/adapter/AguiAgentAdapter.java` (line 144)
- `agentscope-extensions/.../agui/mvc/AguiRestController.java`

**Recommendation**: Add `metadata` field to `AguiMessage`, handle `RequireUserConfirmEvent` in adapter, add confirm endpoint.

---

### #1725 — agentscope-builder 无法以 remote/sandbox 模式启动

**Status**: Bug confirmed — fix needed
**Labels**: bug, area/examples

Two root causes: (1) `agentscope-extensions-redis` not in builder's `pom.xml`, (2) `BuilderConfig.java` hardcodes `RemoteFilesystemSpec` — the `builder.workspace-store.fs-spec` config property is never read.

**Key files**:
- `agentscope-examples/.../agentscope-builder/pom.xml`
- `agentscope-examples/.../builder/web/config/BuilderConfig.java` (lines 213-224)

**Recommendation**: Wire the `fs-spec` property and add Redis dependency. Address together with #1723.

---

### #1723 — `builder.workspace-store.fs-spec` 配置属性从未实现

**Status**: Bug confirmed — fix needed (same root cause as #1725)
**Labels**: bug, area/examples

Property referenced in README (4 times) and README_zh (4 times) but zero Java files read it. `BuilderConfig.java` has `@Value` for other `builder.*` properties but not this one.

**Recommendation**: Fix together with #1725 in one PR.

---

### #1720 — DeepSeek 结构化输出异常

**Status**: Partially fixed — remaining gaps
**Labels**: bug, area/core/model

Core case fixed: `DeepSeekFormatter.supportsNativeStructuredOutput()` returns `false`. But: (1) `DeepSeekMultiAgentFormatter` does NOT override, inheriting `true` from parent. (2) `GLMFormatter` also missing the override. (3) No URL-based safety net for misconfigured models.

**Key files**:
- `agentscope-core/.../formatter/openai/DeepSeekFormatter.java` (line 82-84, correct)
- `agentscope-core/.../formatter/openai/DeepSeekMultiAgentFormatter.java` (missing override)
- `agentscope-core/.../formatter/openai/GLMFormatter.java` (missing override)

**Recommendation**: Add `supportsNativeStructuredOutput()` override returning `false` to both `DeepSeekMultiAgentFormatter` and `GLMFormatter`.

---

### #1717 — streamEvents 增加结构化输出参数

**Status**: Enhancement needed
**Labels**: enhancement, area/core/agent

`streamEvents()` has 6 overloads, none accepting `Class<?>` or `JsonNode`. Non-streaming `call()` has full structured output support. V1 `stream()` had structured output overloads — this is a v2 API regression.

**Key files**:
- `agentscope-core/.../ReActAgent.java` (lines 890-950 streamEvents; 632-660 call with structured output)

**Recommendation**: Add `streamEvents` overloads mirroring `call()` structured output signatures.

---

### #1715 — Skill 在 ReactAgent/HarnessAgent 中无法激活

**Status**: Partially confirmed — HarnessAgent fix needed
**Labels**: bug, area/core/agent, area/core/tool

ReactAgent path appears correct: `DynamicSkillMiddleware` registers `load_skill_through_path` in an active tool group. HarnessAgent path broken: `HarnessSkillMiddleware` installs `SkillLoadTool` which only returns skill content but does NOT call `activateSkill()` or `toolkit.updateToolGroups()` — skill-bound tools are never activated.

**Key files**:
- `agentscope-core/.../skill/DynamicSkillMiddleware.java` (line 123)
- `agentscope-harness/.../skill/runtime/SkillLoadTool.java` (line 113, missing activation)

**Recommendation**: Fix `SkillLoadTool` to activate skill-bound tool groups, mirroring `SkillToolFactory.activateSkill()`.

---

### #1714 — 嵌套 Agent 作为工具无法流式输出

**Status**: Enhancement request
**Labels**: enhancement, area/ext/integration

Core streaming plumbing works (`SubAgentTool` with `forwardEvents`, `AgentSpawnTool` with `SubagentEventBus`). The gap is that `AguiAgentAdapter` treats all events uniformly — doesn't inspect `EventSource` to distinguish parent vs child agent events. Nested agent identity is not surfaced to the frontend.

**Recommendation**: AG-UI adapter needs to forward `EventSource` data (agent identity, nesting depth) so frontend can render nested agent activity distinctly.

---

### #1712 — KubernetesFilesystemSpec 环境变量注入

**Status**: Functionally fixed by commit `fec31c05`
**Labels**: enhancement, area/extensions

`Fabric8KubernetesPodRuntime.createPod()` now injects env vars from `WorkspaceSpec.getEnvironment()`. Minor convenience gap: no direct `environment(Map)` fluent method on `KubernetesFilesystemSpec` (users must go through `WorkspaceSpec`).

**Recommendation**: Can be closed. Optional follow-up for API parity with `DockerFilesystemSpec`.

---

### #1710 — RemoteSandboxSnapshot 反序列化失败

**Status**: Bug exists — fix needed
**Labels**: bug, area/extensions

`SandboxSnapshot` interface's `@JsonTypeInfo(property="type")` conflicts with `RemoteSandboxSnapshot.getType()`. Commit `14c04005` fixed `LocalSandboxSnapshot` with `@JsonIgnoreProperties` and `@JsonCreator`, but `RemoteSandboxSnapshot` and `NoopSandboxSnapshot` still lack these annotations.

**Key files**:
- `agentscope-harness/.../snapshot/RemoteSandboxSnapshot.java` (missing annotations)
- `agentscope-harness/.../snapshot/NoopSandboxSnapshot.java` (missing annotations)

**Recommendation**: Apply same annotation pattern from `LocalSandboxSnapshot` fix to `RemoteSandboxSnapshot` and `NoopSandboxSnapshot`.

---

### #1708 — PostReasoningEvent gotoReasoning 迁移到 v2

**Status**: Question — docs needed
**Labels**: question, area/core/agent

`PostReasoningEvent` and hook system are `@Deprecated(forRemoval=true)` but still functional in 2.0 via `LegacyHookDispatcher`. No built-in v2 middleware equivalent for "re-enter reasoning". Migration paths: `onReasoning` middleware to intercept and re-call, `onActing` to return synthetic results, or `enablePendingToolRecovery(true)` for simple recovery.

**Recommendation**: Add migration guide example for `gotoReasoning` → middleware pattern in docs.

---

### #1707 — Nacos 自动配置 matchIfMissing=true 导致启动失败

**Status**: Bug confirmed — fix needed
**Labels**: bug, area/ext/spring-boot

All three auto-config classes use `matchIfMissing=true`, activating by default. `AgentscopeA2aNacosAutoConfiguration` has no `@ConditionalOnClass` guard — most dangerous. Additionally, A2A config's `getNacosProperties()` silently overwrites user's `server-addr` via `putAll`.

**Key files**:
- `agentscope-extensions/.../nacos/AgentscopeNacosPromptAutoConfiguration.java` (line 53-57)
- `agentscope-extensions/.../nacos/AgentscopeA2aNacosAutoConfiguration.java` (line 53-57, 84)

**Recommendation**: Change `matchIfMissing` to `false`, add `@ConditionalOnClass` to A2A config, use `getExplicitNacosProperties()` instead of `getNacosProperties()`.

---

### #1706 — 扩展层初始化方式不统一

**Status**: Enhancement request
**Labels**: enhancement, area/extensions

Confirmed: within the same package, OSS/Redis/MySQL extensions use a mix of `builder()`, `new`, `create()`, `fromJedis()`. Unifying to builder pattern is reasonable but a breaking change.

**Recommendation**: Track as enhancement. Unify gradually in future releases.

---

### #1705 — 中文 Skill 名路径编码异常

**Status**: Bug exists — fix needed
**Labels**: bug, area/core/tool

6 `Path.resolve(skillName)` call sites with zero encoding protection. In Docker with POSIX locale, `sun.jnu.encoding` falls back to ASCII, causing `InvalidPathException` for Chinese names. No Dockerfile sets `LANG=C.UTF-8` or JVM encoding flags.

**Key files**:
- `agentscope-core/.../skill/util/SkillFileSystemHelper.java` (line 318)
- `agentscope-harness/.../skill/runtime/MarketplaceStager.java` (line 135)
- `agentscope-harness/.../skill/runtime/ShellPathPolicy.java` (lines 117, 136)
- `agentscope-core/.../skill/SkillBox.java` (line 784)

**Recommendation**: Set `ENV LANG=C.UTF-8` in Dockerfiles and add `InvalidPathException` handling or URL-encode skill names before `Path.resolve()`.

---

### #1704 — RC2 基本文件操作和 Skill 加载不通

**Status**: Needs more information
**Labels**: bug, area/examples, area/harness

Code-level path construction appears symmetric across write/list operations. The asymmetry the user reports may stem from IsolationScope/namespace mismatch, mixing core and harness tools, or configuration issues.

**Recommendation**: Request exact tool names, agent config, and isolation scope from reporter. Cannot reproduce from code analysis alone.

---

### #1702 — 沙箱模式下工具无法获取类型安全上下文

**Status**: Already fixed (commit `45abd288`)
**Labels**: bug, area/harness

Commit `45abd288` ("fix: preserve typed runtime context across copies") introduced `RuntimeContext.builder(source)` that deep-copies all context data including typed attributes. 8 new tests added.

**Recommendation**: Close the issue.

---

### #1700 — HarnessGateway.runSubagentStream 缺少 RuntimeContext

**Status**: Bug exists — fix needed
**Labels**: bug, area/harness

`runSubagentStream()` builds minimal `RuntimeContext` with only `sessionId`. Missing: `userId`, `MsgContext`, `gateKey`, `outboundAddress`. `runSubagent()` has the identical bug. `exposeSubagent()` stores `null` for userId in `SubagentRecord`.

**Key files**:
- `agentscope-harness/.../gateway/HarnessGateway.java` (lines 365, 414)
- `agentscope-harness/.../gateway/Gateway.java` interface

**Recommendation**: Add `MsgContext`/`userId` to `Gateway` interface signatures. Build full `RuntimeContext` matching what `runStream()` does.

---

## Priority Classification

### P0 — Critical bugs (fix immediately)
| Issue | Title | Reason |
|-------|-------|--------|
| #1707 | Nacos matchIfMissing=true | Crashes startup for all Nacos starter users |
| #1729 | K8s sandbox workspace failure | All K8s sandbox users affected |
| #1705 | Chinese skill name encoding | Production crash in Docker |

### P1 — Important bugs (fix soon)
| Issue | Title |
|-------|-------|
| #1731 | Subagent RuntimeContext loss |
| #1700 | HarnessGateway subagent RuntimeContext |
| #1737 | CompositeFilesystem.ls() root routing |
| #1726 | AG-UI HITL confirm flow broken |
| #1740 | compressIfNeeded() single pass |
| #1710 | RemoteSandboxSnapshot deserialization |
| #1715 | Skill activation in HarnessAgent |
| #1720 | DeepSeek multi-agent structured output |

### P2 — Moderate (plan for next release)
| Issue | Title |
|-------|-------|
| #1725 + #1723 | Builder fs-spec not implemented |
| #1728 | McpClientBuilder dynamic token |
| #1717 | streamEvents structured output |

### P3 — Enhancement / Question
| Issue | Title | Type |
|-------|-------|------|
| #1733 | AG-UI multimodal | Enhancement |
| #1714 | Nested agent streaming | Enhancement |
| #1706 | Init pattern unification | Enhancement |
| #1736 | MysqlSkillRepository sample data | Enhancement |
| #1730 | Spring AI Alibaba integration | Question |
| #1708 | gotoReasoning migration | Question / Docs |
| #1739 | Child agent session design | Clarification |
| #1704 | File tool path issues | Needs info |

### Already Fixed
| Issue | Title | Fixed by |
|-------|-------|----------|
| #1702 | Sandbox typed context | Commit `45abd288` |
| #1712 | K8s env var injection | Commit `fec31c05` |
