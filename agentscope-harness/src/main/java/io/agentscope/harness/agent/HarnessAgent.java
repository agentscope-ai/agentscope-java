/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import io.agentscope.harness.agent.middleware.AtPathExpansionMiddleware;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware;
import io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.curator.RejectAllGate;
import io.agentscope.harness.agent.skill.curator.SkillAuditLog;
import io.agentscope.harness.agent.skill.curator.SkillCurator;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillPromoter;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.store.NamespaceFactory;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.MemoryGetTool;
import io.agentscope.harness.agent.tool.MemorySearchTool;
import io.agentscope.harness.agent.tool.PlanModeTools;
import io.agentscope.harness.agent.tool.ProposeSkillTool;
import io.agentscope.harness.agent.tool.SessionSearchTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.tool.SkillManageTool;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.ToolFilter;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.tools.ToolsConfigLoader;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * HarnessAgent is the user-facing harness API that wraps a {@link ReActAgent} with workspace /
 * filesystem / sandbox / subagent / skill / plan-mode / MCP orchestration.
 *
 * <p>Use {@link #builder()} to configure. For plain ReAct usage without any of the above, use
 * {@link ReActAgent#builder()} directly.
 *
 * <p>Capabilities added on top of the inner {@link ReActAgent}:
 *
 * <ul>
 *   <li>Workspace-based context loading (AGENTS.md, MEMORY.md, KNOWLEDGE.md)</li>
 *   <li>Pluggable file-system backend (local, sandbox, remote/composite)</li>
 *   <li>Subagent orchestration via {@code task} / {@code task_output} tools (sync + background)</li>
 *   <li>Skill loading via {@link AgentSkillRepository}, including the M1–M7 self-learning loop</li>
 *   <li>Memory flush + message offload before context compression</li>
 *   <li>Workspace-managed {@code tools.json} (MCP servers + allow/deny filter)</li>
 *   <li>Plan mode (read-only design phase) with {@code plan_enter}/{@code plan_write}/{@code plan_exit} tools</li>
 *   <li>Context-overflow emergency compaction via {@link CompactionMiddleware}</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> {@code HarnessAgent} is stateless between calls and safe to use as a
 * singleton serving multiple users/sessions concurrently. Each {@code call()} uses the
 * {@link io.agentscope.core.agent.RuntimeContext}'s {@code (userId, sessionId)} to isolate state.
 * Calls targeting the same session are serialized automatically; different sessions run in parallel.
 */
public class HarnessAgent implements Agent, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgent.class);

    private final ReActAgent delegate;
    private final WorkspaceManager workspaceManager;
    private final BiFunction<String, String, WorkspaceManager> workspaceFactory;
    private final WorkspaceIndex ownedWorkspaceIndex;
    private final SandboxContext defaultSandboxContext;
    private final CompactionMiddleware compactionHook;
    private final SandboxLifecycleMiddleware sandboxLifecycleMw;
    private final List<AgentSkillRepository> skillRepositories;
    private final PlanModeManager planModeManager;
    // Skill self-learning (M4/M5/M7) — null unless enableSkillManageTool / enableSkillCurator.
    private final SkillPromoter skillPromoter;
    private final SkillUsageStore skillUsageStore;
    private final SkillCurator skillCurator;
    private final SkillAuditLog skillAuditLog;
    private final MemoryConfig memoryConfig;

    private HarnessAgent(
            ReActAgent delegate,
            WorkspaceManager workspaceManager,
            BiFunction<String, String, WorkspaceManager> workspaceFactory,
            WorkspaceIndex ownedWorkspaceIndex,
            SandboxContext defaultSandboxContext,
            CompactionMiddleware compactionHook,
            SandboxLifecycleMiddleware sandboxLifecycleMw,
            List<AgentSkillRepository> skillRepositories,
            PlanModeManager planModeManager,
            SkillPromoter skillPromoter,
            SkillUsageStore skillUsageStore,
            SkillCurator skillCurator,
            SkillAuditLog skillAuditLog,
            MemoryConfig memoryConfig) {
        this.delegate = delegate;
        this.workspaceManager = workspaceManager;
        this.workspaceFactory = workspaceFactory;
        this.ownedWorkspaceIndex = ownedWorkspaceIndex;
        this.defaultSandboxContext = defaultSandboxContext;
        this.compactionHook = compactionHook;
        this.sandboxLifecycleMw = sandboxLifecycleMw;
        this.skillRepositories =
                skillRepositories != null ? List.copyOf(skillRepositories) : List.of();
        this.planModeManager = planModeManager;
        this.skillPromoter = skillPromoter;
        this.skillUsageStore = skillUsageStore;
        this.skillCurator = skillCurator;
        this.skillAuditLog = skillAuditLog;
        this.memoryConfig = memoryConfig != null ? memoryConfig : MemoryConfig.defaults();
    }

    /** Returns the workspace manager bound to this agent, or {@code null} if not configured. */
    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    /**
     * Returns a {@link WorkspaceManager} view whose filesystem and namespace are bound to the
     * given {@code (userId, sessionId)} for the duration of the returned view's IO. Unlike
     * {@link #getWorkspaceManager()}, this does not mutate any shared state on this agent — so it
     * is safe to call concurrently from per-request controllers without racing with active chats.
     */
    public WorkspaceManager workspaceFor(String userId, String sessionId) {
        if (workspaceFactory == null) {
            return workspaceManager;
        }
        return workspaceFactory.apply(userId, sessionId);
    }

    /** Returns the {@link CompactionMiddleware} instance if compaction was configured, or {@code null}. */
    public CompactionMiddleware getCompactionHook() {
        return compactionHook;
    }

    /**
     * Returns the ordered list of {@link AgentSkillRepository} instances bound to this agent (low
     * to high priority).
     */
    public List<AgentSkillRepository> getSkillRepositories() {
        return skillRepositories;
    }

    /** Access to the sidecar telemetry store (M2/M4). Null when {@code enableSkillManageTool}
     * was not configured. */
    public SkillUsageStore getSkillUsageStore() {
        return skillUsageStore;
    }

    /**
     * Query the audit log for a given UTC day. Pass {@code null} for "today". Returns an empty
     * list when the audit log is not configured (no {@code enableSkillManageTool} call).
     */
    public List<SkillAuditLog.Entry> queryAudit(
            String dayUtc, java.util.function.Predicate<SkillAuditLog.Entry> filter) {
        if (skillAuditLog == null) {
            return List.of();
        }
        return skillAuditLog.query(dayUtc, filter);
    }

    /**
     * Force-run the skill curator immediately, bypassing the idle-and-interval gate. Returns
     * a {@code Mono} that emits {@code null} when the curator is not configured.
     */
    public Mono<SkillCurator.CuratorRunReport> runCuratorOnce() {
        if (skillCurator == null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> skillCurator.runOnce(null));
    }

    /**
     * Promote a draft skill from {@code skills/_drafts/} to the live skills root via the
     * configured {@link SkillPromotionGate}.
     */
    public Mono<SkillPromoter.PromotionResult> promoteSkill(String name, String reviewerId) {
        if (skillPromoter == null) {
            return Mono.just(
                    SkillPromoter.PromotionResult.invalid(
                            "skill promoter not configured; call"
                                    + " enableSkillManageTool(...) on the builder"));
        }
        return skillPromoter.promote(name, reviewerId, getRuntimeContext());
    }

    /**
     * Programmatically enters plan mode (read-only design phase). Persisted in {@link AgentState}
     * so it survives restarts / hand-offs.
     */
    public void enterPlanMode() {
        AgentState s = getAgentState();
        if (s == null) {
            return;
        }
        if (planModeManager != null) {
            planModeManager.enter(s);
        } else {
            s.getPlanModeContext().setPlanActive(true);
        }
    }

    /** Programmatically exits plan mode (back to BUILD). Persisted in {@link AgentState}. */
    public void exitPlanMode() {
        AgentState s = getAgentState();
        if (s == null) {
            return;
        }
        if (planModeManager != null) {
            planModeManager.exit(s);
        } else {
            s.getPlanModeContext().setPlanActive(false);
        }
    }

    /** @return whether plan mode is currently active for this agent. */
    public boolean isPlanModeActive() {
        AgentState s = getAgentState();
        return s != null && s.getPlanModeContext().isPlanActive();
    }

    /**
     * Enters plan mode for the given {@code (userId, sessionId)} session, independent of which slot
     * is currently active. The change is persisted so the next {@code call} on that session sees
     * it.
     */
    public void enterPlanMode(String userId, String sessionId) {
        AgentState s = delegate.getAgentState(userId, sessionId);
        if (planModeManager != null) {
            planModeManager.enter(s);
        } else {
            s.getPlanModeContext().setPlanActive(true);
        }
        delegate.saveAgentState(userId, sessionId);
    }

    /** Exits plan mode for the given {@code (userId, sessionId)} session and persists the change. */
    public void exitPlanMode(String userId, String sessionId) {
        AgentState s = delegate.getAgentState(userId, sessionId);
        if (planModeManager != null) {
            planModeManager.exit(s);
        } else {
            s.getPlanModeContext().setPlanActive(false);
        }
        delegate.saveAgentState(userId, sessionId);
    }

    /** @return whether plan mode is active for the given {@code (userId, sessionId)} session. */
    public boolean isPlanModeActive(String userId, String sessionId) {
        AgentState s = delegate.getAgentState(userId, sessionId);
        return s.getPlanModeContext().isPlanActive();
    }

    @Override
    public void close() {
        try {
            if (ownedWorkspaceIndex != null) {
                ownedWorkspaceIndex.close();
            }
        } finally {
            delegate.close();
        }
    }

    // ==================== Agent interface delegation ====================

    /** Returns the wrapped {@link ReActAgent}. */
    public ReActAgent getDelegate() {
        return delegate;
    }

    public Model getModel() {
        return delegate.getModel();
    }

    public int getMaxIters() {
        return delegate.getMaxIters();
    }

    public RuntimeContext getRuntimeContext() {
        return delegate.getRuntimeContext();
    }

    public AgentStateStore getStateStore() {
        return delegate.getStateStore();
    }

    /** @see ReActAgent#getDefaultSessionId() */
    public String getDefaultSessionId() {
        return delegate.getDefaultSessionId();
    }

    /** @see ReActAgent#getCurrentSessionId() */
    public String getCurrentSessionId() {
        return delegate.getCurrentSessionId();
    }

    /** @see ReActAgent#getCurrentUserId() */
    public String getCurrentUserId() {
        return delegate.getCurrentUserId();
    }

    @Override
    public AgentState getAgentState() {
        return delegate.getAgentState();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getAgentId() {
        return delegate.getAgentId();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public void interrupt() {
        delegate.interrupt();
    }

    @Override
    public void interrupt(Msg msg) {
        delegate.interrupt(msg);
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return wrappedCall(msgs, RuntimeContext.empty(), () -> delegate.call(msgs));
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
        return wrappedCall(
                msgs, RuntimeContext.empty(), () -> delegate.call(msgs, structuredModel));
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return wrappedCall(msgs, RuntimeContext.empty(), () -> delegate.call(msgs, schema));
    }

    public Mono<Msg> call(Msg msg, RuntimeContext ctx) {
        return call(List.of(msg), ctx);
    }

    public Mono<Msg> call(List<Msg> msgs, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedCall(msgs, effective, () -> delegate.call(msgs, effective));
    }

    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedCall(msgs, effective, () -> delegate.call(msgs, structuredModel, effective));
    }

    public Mono<Msg> call(List<Msg> msgs, JsonNode schema, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedCall(msgs, effective, () -> delegate.call(msgs, schema, effective));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} for the fine-grained
     *     {@code AgentEvent} stream that aligns with Python 2.0's {@code agent.reply_stream()}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return wrappedStream(RuntimeContext.empty(), () -> delegate.stream(msgs, options));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} for the fine-grained
     *     {@code AgentEvent} stream.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return wrappedStream(
                RuntimeContext.empty(), () -> delegate.stream(msgs, options, structuredModel));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List)} for the fine-grained
     *     {@code AgentEvent} stream.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return wrappedStream(RuntimeContext.empty(), () -> delegate.stream(msgs, options, schema));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(Msg, RuntimeContext)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(Msg msg, RuntimeContext ctx) {
        return stream(List.of(msg), StreamOptions.defaults(), ctx);
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List, RuntimeContext)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(List<Msg> msgs, RuntimeContext ctx) {
        return stream(msgs, StreamOptions.defaults(), ctx);
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List, RuntimeContext)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStream(effective, () -> delegate.stream(msgs, options, effective));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List, RuntimeContext)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, Class<?> structuredModel, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStream(
                effective, () -> delegate.stream(msgs, options, structuredModel, effective));
    }

    /**
     * @deprecated since 2.0.0, for removal. Use {@link #streamEvents(List, RuntimeContext)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public Flux<Event> stream(
            List<Msg> msgs, StreamOptions options, JsonNode schema, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStream(effective, () -> delegate.stream(msgs, options, schema, effective));
    }

    // ==================== streamEvents (AgentEvent — v2 aligned) ====================

    /**
     * Stream fine-grained {@link AgentEvent}s for a single message. Aligns with Python 2.0's
     * {@code agent.reply_stream()} signature.
     *
     * @param msg input message
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(Msg msg) {
        return streamEvents(List.of(msg), RuntimeContext.empty());
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a list of messages.
     *
     * @param msgs input messages
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        return streamEvents(msgs, RuntimeContext.empty());
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a single message with a caller-supplied
     * {@link RuntimeContext}.
     *
     * @param msg input message
     * @param ctx runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(Msg msg, RuntimeContext ctx) {
        return streamEvents(List.of(msg), ctx);
    }

    /**
     * Stream fine-grained {@link AgentEvent}s for a list of messages with a caller-supplied
     * {@link RuntimeContext}. The harness wraps the delegate's
     * {@code ReActAgent#streamEvents(List, RuntimeContext)} with the same sandbox-lifecycle
     * acquire/release semantics that the {@code call(...)} family uses, so streaming and
     * blocking callers behave consistently with respect to sandbox warm-up.
     *
     * <p><b>Note on subagent events:</b> child-agent events spawned via {@code agent_spawn} /
     * {@code agent_send} are currently forwarded only on the deprecated {@link #stream(List,
     * StreamOptions, RuntimeContext)} path (typed as {@code io.agentscope.core.agent.Event} with
     * {@code EventSource}). The equivalent {@code AgentEvent} source channel is on the v2 roadmap;
     * until it lands, this method emits parent events only.
     *
     * @param msgs input messages
     * @param ctx runtime context to propagate into the call
     * @return event stream covering the full agent invocation lifecycle
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext ctx) {
        RuntimeContext effective =
                ensureSessionDefaults(ctx != null ? ctx : RuntimeContext.empty());
        return wrappedStreamEvents(effective, () -> delegate.streamEvents(msgs, effective));
    }

    @Override
    public Mono<Void> observe(Msg msg) {
        return delegate.observe(msg);
    }

    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        return delegate.observe(msgs);
    }

    public Toolkit getToolkit() {
        return delegate.getToolkit();
    }

    // ==================== Call/stream wrappers ====================

    private Mono<Msg> wrappedCall(
            List<Msg> msgs, RuntimeContext effective, Supplier<Mono<Msg>> inner) {
        Mono<Msg> base =
                Mono.using(
                        () -> {
                            if (sandboxLifecycleMw != null) {
                                sandboxLifecycleMw.acquireForCall(effective);
                            }
                            return effective;
                        },
                        eff -> inner.get(),
                        eff -> {
                            if (sandboxLifecycleMw != null) {
                                sandboxLifecycleMw.releaseForCall(eff);
                            }
                        });
        if (compactionHook != null) {
            return base.onErrorResume(
                    e -> {
                        if (isContextOverflowError(e)) {
                            return recoverFromOverflow(msgs, effective);
                        }
                        return Mono.error(e);
                    });
        }
        return base;
    }

    /**
     * @deprecated since 2.0.0, for removal alongside the {@link #stream(List, StreamOptions)}
     *     family. Replaced by {@link #wrappedStreamEvents(RuntimeContext, Supplier)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    private Flux<Event> wrappedStream(RuntimeContext effective, Supplier<Flux<Event>> inner) {
        return Flux.using(
                () -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.acquireForCall(effective);
                    }
                    return effective;
                },
                eff -> inner.get(),
                eff -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.releaseForCall(eff);
                    }
                });
    }

    private Flux<AgentEvent> wrappedStreamEvents(
            RuntimeContext effective, Supplier<Flux<AgentEvent>> inner) {
        return Flux.using(
                () -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.acquireForCall(effective);
                    }
                    return effective;
                },
                eff -> inner.get(),
                eff -> {
                    if (sandboxLifecycleMw != null) {
                        sandboxLifecycleMw.releaseForCall(eff);
                    }
                });
    }

    /**
     * Fills in a default {@code sessionId} when the caller didn't provide one, and injects the
     * default sandbox context. The agent's persistence backend is bound at builder time via
     * {@code .stateStore(...)}; the per-call routing is via {@code (userId, sessionId)} on the
     * RuntimeContext (consumed by {@code ReActAgent.activateSlotForContext}).
     */
    private RuntimeContext ensureSessionDefaults(RuntimeContext ctx) {
        String ctxSessionId = ctx.getSessionId();
        if (ctxSessionId == null || ctxSessionId.isBlank()) {
            ctxSessionId = getName();
        }
        SandboxContext sandboxCtx =
                ctx.get(SandboxContext.class) != null
                        ? ctx.get(SandboxContext.class)
                        : defaultSandboxContext;

        if (ctxSessionId.equals(ctx.getSessionId())
                && sandboxCtx == ctx.get(SandboxContext.class)) {
            return ctx;
        }
        return RuntimeContext.builder()
                .sessionId(ctxSessionId)
                .userId(ctx.getUserId())
                .putAll(ctx.getExtra())
                .put(SandboxContext.class, sandboxCtx)
                .build();
    }

    private Mono<Msg> recoverFromOverflow(List<Msg> msgs, RuntimeContext effective) {
        if (compactionHook != null) {
            log.warn(
                    "Context overflow detected, triggering emergency compaction via"
                            + " CompactionMiddleware");
            return forceCompactAndRetry(msgs, effective);
        }
        return Mono.error(
                new RuntimeException(
                        "Context overflow: no compaction configured, unable to recover"));
    }

    private Mono<Msg> forceCompactAndRetry(List<Msg> msgs, RuntimeContext effective) {
        AgentState state = RuntimeContext.resolveAgentState(effective, delegate);
        List<Msg> allMsgs = state.contextMutable();
        if (allMsgs.isEmpty()) {
            return Mono.error(
                    new RuntimeException("Context overflow: context is empty, cannot compact"));
        }
        String agentId = getName();
        String sessionId =
                effective != null && effective.getSessionId() != null
                        ? effective.getSessionId()
                        : "default";

        CompactionConfig forceConfig = CompactionConfig.builder().triggerMessages(1).build();
        String effectiveFlushPrompt =
                memoryConfig.flushPrompt() != null
                        ? memoryConfig.flushPrompt()
                        : MemoryFlushManager.DEFAULT_FLUSH_PROMPT;
        MemoryFlushManager fm =
                new MemoryFlushManager(workspaceManager, getModel(), effectiveFlushPrompt);
        ConversationCompactor compactor = new ConversationCompactor(getModel(), fm);

        return compactor
                .compactIfNeeded(
                        effective != null ? effective : RuntimeContext.empty(),
                        allMsgs,
                        forceConfig,
                        agentId,
                        sessionId)
                .flatMap(
                        opt -> {
                            if (opt.isPresent()) {
                                state.contextMutable().clear();
                                state.contextMutable().addAll(opt.get());
                                return delegate.call(
                                        msgs,
                                        effective != null ? effective : RuntimeContext.empty());
                            }
                            return Mono.error(
                                    new RuntimeException(
                                            "Context overflow: emergency compaction yielded no"
                                                    + " result"));
                        });
    }

    private static boolean isContextOverflowError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("context_length_exceeded")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("token limit")
                || lower.contains("too many tokens")
                || lower.contains("exceeds the model's maximum")
                || lower.contains("reduce the length");
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Default state directory for the built-in {@link JsonFileAgentStateStore} backend:
     * {@code ~/.agentscope/state/<agentId>/}. Lives outside any workspace so agent state
     * (a prerequisite for restoring the workspace via {@link
     * io.agentscope.harness.agent.sandbox.SandboxState#getWorkspaceSpec()}) is not entangled
     * with workspace data.
     *
     * <p>The base directory ({@code ~/.agentscope/state}) can be overridden via the
     * {@code agentscope.state.home} system property. This is primarily for tests / CI to
     * redirect state into a build-scoped temporary directory rather than polluting the
     * developer's real home directory; production deployments generally don't need it.
     */
    static Path defaultStateDir(String agentId) {
        String override = System.getProperty("agentscope.state.home");
        Path root =
                override != null && !override.isBlank()
                        ? Paths.get(override)
                        : Paths.get(System.getProperty("user.home"), ".agentscope", "state");
        return root.resolve(agentId);
    }

    /**
     * Returns true when the given session is a local in-process implementation that cannot share
     * state across nodes. Used by sandbox / remote-filesystem fail-fast checks to reject
     * configurations that would silently leak per-node state in distributed deployments.
     */
    static boolean isLocalSession(AgentStateStore stateStore) {
        return stateStore instanceof JsonFileAgentStateStore
                || stateStore instanceof InMemoryAgentStateStore;
    }

    // ==================== Builder ====================

    /**
     * Builder for {@link HarnessAgent}. Owns the harness orchestration: workspace + filesystem +
     * sandbox + hooks/middlewares + tools + skills + subagents + tools.json + plan-mode.
     */
    public static class Builder {

        private final ReActAgent.Builder inner = ReActAgent.builder();

        // ---- Mirrored fields readable by HarnessAgentBuilderSupport ----
        // These mirror the values forwarded to `inner` so the helpers + subagent factories
        // can read them without crossing package-private boundaries on ReActAgent.Builder.

        String name;
        String description;
        String sysPrompt;
        boolean checkRunning = true;
        Model model;
        Toolkit toolkit = new Toolkit();
        int maxIters = 10;
        ExecutionConfig modelExecutionConfig;
        ExecutionConfig toolExecutionConfig;
        GenerateOptions generateOptions;
        final Set<Hook> hooks = new LinkedHashSet<>();

        // ---- Harness orchestration fields ----

        String agentId;
        final List<AgentSkillRepository> skillRepositories = new ArrayList<>();
        Path projectGlobalSkillsDir;

        Path workspace;
        String environmentMemory;
        AbstractFilesystem abstractFilesystem;
        boolean leafSubagent = false;
        boolean agentTracingLogEnabled = true;
        CompactionConfig compactionConfig = null;
        MemoryConfig memoryConfig = MemoryConfig.defaults();
        ToolResultEvictionConfig toolResultEvictionConfig = null;

        final List<SubagentDeclaration> subagentDeclarations = new ArrayList<>();
        final List<HarnessAgentBuilderSupport.SubagentFactoryEntry> customSubagentFactories =
                new ArrayList<>();
        TaskRepository taskRepository;
        Object externalSubagentTool;
        Function<String, Model> modelResolver;
        final List<String> additionalContextFiles = new ArrayList<>();
        int maxContextTokens = 8000;
        boolean useLegacyXmlWorkspaceContext = false;

        boolean disableFilesystemTools = false;
        boolean disableShellTool = false;
        boolean disableMemoryTools = false;
        boolean disableMemoryHooks = false;
        boolean disableSessionPersistence = false;
        boolean disableWorkspaceContext = false;
        boolean disableAtPathExpansion = false;
        boolean disableSubagents = false;
        boolean disableDynamicSkills = false;
        boolean disableDefaultWorkspaceSkills = false;
        boolean disableDynamicSubagents = false;
        boolean disableToolsConfig = false;

        boolean skillManageToolEnabled = false;
        SkillManageConfig skillManageConfig;
        SkillPromotionGate promotionGate;
        SkillVisibilityFilter visibilityFilter;
        String environment = "prod";
        boolean skillCuratorEnabled = false;
        SkillCuratorConfig skillCuratorConfig;
        io.agentscope.core.skill.SkillFilter skillFilter;

        boolean planModeEnabled = false;
        String planFileDir = PlanModeManager.DEFAULT_PLAN_DIR;

        ToolsConfig toolsConfigOverride;

        SandboxFilesystemSpec sandboxFilesystemSpec;
        RemoteFilesystemSpec remoteFilesystemSpec;
        LocalFilesystemSpec localFilesystemSpec;

        // AgentStateStore — mirrored only to pass through to inner; the user-set AgentStateStore
        // can also be replaced inside orchestration when none is provided (defaults to a
        // JsonFileAgentStateStore rooted at ~/.agentscope/state/<agentId>/, outside any workspace).
        AgentStateStore stateStoreOverride;

        private Builder() {}

        /**
         * Returns a new {@link Builder} pre-populated with as much of the given {@link ReActAgent}'s
         * observable configuration as can be read back from public getters.
         *
         * <p>This is a <b>partial</b> migration helper. The caller still needs to set every
         * harness-specific concern explicitly (workspace, filesystem, sandbox, subagents, skills,
         * plan mode, etc.) — those have no analog on a vanilla {@link ReActAgent}, so they cannot
         * be derived from {@code agent}.
         *
         * <h4>What this method copies</h4>
         *
         * <table border="1">
         *   <caption>Fields copied from the source ReActAgent</caption>
         *   <tr><th>Group</th><th>Field</th><th>Source</th></tr>
         *   <tr><td rowspan="7">Observable configuration</td>
         *       <td>{@code name}</td><td>{@code agent.getName()}</td></tr>
         *   <tr><td>{@code description}</td><td>{@code agent.getDescription()}</td></tr>
         *   <tr><td>{@code sysPrompt}</td><td>{@code agent.getSysPrompt()}</td></tr>
         *   <tr><td>{@code model}</td><td>{@code agent.getModel()}</td></tr>
         *   <tr><td>{@code maxIters}</td><td>{@code agent.getMaxIters()}</td></tr>
         *   <tr><td>{@code generateOptions}</td><td>{@code agent.getGenerateOptions()}</td></tr>
         *   <tr><td>{@code toolkit}</td><td>defensive copy via {@code agent.getToolkit().copy()}</td></tr>
         *   <tr><td rowspan="2">Persistence</td>
         *       <td>{@code session}</td><td>{@code agent.getStateStore()} if non-null</td></tr>
         *   <tr><td>{@code defaultSessionId}</td><td>{@code agent.getDefaultSessionId()} if non-null</td></tr>
         *   <tr><td rowspan="2">Model resilience (from {@code agent.getModelConfig()})</td>
         *       <td>{@code maxRetries}</td><td>{@link ModelConfig#maxRetries()}</td></tr>
         *   <tr><td>{@code fallbackModel}</td><td>{@link ModelConfig#fallbackModel()} if non-null</td></tr>
         *   <tr><td>Reasoning loop (from {@code agent.getReactConfig()})</td>
         *       <td>{@code stopOnReject}</td><td>{@link ReactConfig#stopOnReject()}</td></tr>
         *   <tr><td rowspan="2">Execution</td>
         *       <td>{@code modelExecutionConfig}</td><td>{@code agent.getModelExecutionConfig()} if non-null</td></tr>
         *   <tr><td>{@code toolExecutionConfig}</td><td>{@code agent.getToolExecutionConfig()} if non-null</td></tr>
         *   <tr><td rowspan="4">Behavior</td>
         *       <td>{@code toolExecutionContext}</td><td>{@code agent.getToolExecutionContext()} if non-null</td></tr>
         *   <tr><td>{@code structuredOutputReminder}</td><td>{@code agent.getStructuredOutputReminder()}</td></tr>
         *   <tr><td>{@code enablePendingToolRecovery}</td><td>{@code agent.isPendingToolRecoveryEnabled()}</td></tr>
         *   <tr><td>{@code checkRunning}</td><td>{@code agent.isCheckRunning()}</td></tr>
         *   <tr><td>Permissions</td>
         *       <td>{@code permissionContext}</td><td>{@code agent.getPermissionContext()} if non-null
         *           (the same {@link PermissionContextState} is reused; it carries the rules registered
         *           on the source engine)</td></tr>
         *   <tr><td>Extension surface</td>
         *       <td>{@code middlewares}</td><td>{@code agent.getMiddlewares()} appended as-is</td></tr>
         *   <tr><td>Legacy extension</td>
         *       <td>{@code hooks}</td><td>{@code agent.getHooks()} appended as-is ({@link Hook}
         *           itself is {@code @Deprecated(forRemoval=true)}; prefer middlewares for new
         *           code)</td></tr>
         * </table>
         *
         * <p>Note: {@code enableMetaTool} and {@code enableTaskList} are builder-time flags that
         * mutate the toolkit at build. They do not round-trip as flags, but the toolkit copy
         * <i>already</i> carries the tools they registered, so the resulting agent has the same
         * tool surface.
         *
         * <h4>What this method does <b>not</b> copy</h4>
         *
         * <p><b>Skipped — harness-only, has no source on a {@code ReActAgent}.</b> These
         * <i>must</i> be configured on the returned builder if you want HarnessAgent semantics:
         * <ul>
         *   <li>Workspace &amp; filesystem: {@link #workspace(Path)}, {@link #filesystem(SandboxFilesystemSpec)},
         *       {@link #filesystem(LocalFilesystemSpec)}, {@link #filesystem(RemoteFilesystemSpec)},
         *       {@link #abstractFilesystem(AbstractFilesystem)},
         *       {@link #environmentMemory(String)}</li>
         *   <li>Subagents: {@link #subagent(SubagentDeclaration)}, {@link #subagents(List)},
         *       {@link #subagentFactory(String, Function)}, {@link #externalSubagentTool(Object)},
         *       {@link #taskRepository(TaskRepository)}, {@link #modelResolver(Function)}</li>
         *   <li>Skill governance: {@link #skillRepository(AgentSkillRepository)},
         *       {@link #projectGlobalSkillsDir(Path)},
         *       {@link #enableSkillManageTool(SkillManageConfig)},
         *       {@link #enableSkillCurator(SkillCuratorConfig)},
         *       {@link #enableSkillPromotionGate(SkillPromotionGate, SkillVisibilityFilter)},
         *       {@link #skillFilter(io.agentscope.core.skill.SkillFilter)},
         *       {@link #environment(String)}</li>
         *   <li>Plan mode: {@link #enablePlanMode()}, {@link #planFileDirectory(String)}</li>
         *   <li>Context engineering: {@link #additionalContextFile(String)},
         *       {@link #maxContextTokens(int)}, {@link #compaction(CompactionConfig)},
         *       {@link #toolResultEviction(ToolResultEvictionConfig)},
         *       {@link #toolsConfig(ToolsConfig)}</li>
         *   <li>All {@code disableXxx()} toggles and {@link #enableAgentTracingLog(boolean)}</li>
         * </ul>
         *
         * <h4>Behavior caveats</h4>
         *
         * <p>Even after this method, the built {@code HarnessAgent} is <b>not</b> behaviorally
         * equivalent to the source {@code ReActAgent}: HarnessAgent installs additional
         * orchestration (workspace projection, agent-tracing middleware, default skill /
         * subagent middlewares) that the source did not have. If left unset, {@code session}
         * also defaults to a {@code JsonFileAgentStateStore} rooted at {@code ~/.agentscope/state/<agentId>/}
         * rather than the in-memory default, changing the on-disk persistence layout.
         *
         * @param agent source {@link ReActAgent} to inherit observable configuration from
         * @return a new {@link Builder} pre-populated with the inheritable subset
         */
        public static Builder fromAgent(ReActAgent agent) {
            Builder b = new Builder();

            // Observable configuration.
            b.name(agent.getName());
            b.description(agent.getDescription());
            b.sysPrompt(agent.getSysPrompt());
            b.model(agent.getModel());
            b.maxIters(agent.getMaxIters());
            b.generateOptions(agent.getGenerateOptions());
            b.toolkit(agent.getToolkit().copy());

            // Persistence.
            AgentStateStore srcSession = agent.getStateStore();
            if (srcSession != null) {
                b.stateStore(srcSession);
            }
            String srcDefaultSessionId = agent.getDefaultSessionId();
            if (srcDefaultSessionId != null) {
                b.defaultSessionId(srcDefaultSessionId);
            }

            // Model resilience.
            ModelConfig mc = agent.getModelConfig();
            if (mc != null) {
                b.maxRetries(mc.maxRetries());
                if (mc.fallbackModel() != null) {
                    b.fallbackModel(mc.fallbackModel());
                }
            }

            // Reasoning loop. maxIters already covered above; only stopOnReject left.
            ReactConfig rc = agent.getReactConfig();
            if (rc != null) {
                b.stopOnReject(rc.stopOnReject());
            }

            // Execution configs.
            ExecutionConfig srcModelExec = agent.getModelExecutionConfig();
            if (srcModelExec != null) {
                b.modelExecutionConfig(srcModelExec);
            }
            ExecutionConfig srcToolExec = agent.getToolExecutionConfig();
            if (srcToolExec != null) {
                b.toolExecutionConfig(srcToolExec);
            }

            // Behavior flags + tool execution context.
            ToolExecutionContext srcToolCtx = agent.getToolExecutionContext();
            if (srcToolCtx != null) {
                b.toolExecutionContext(srcToolCtx);
            }
            b.structuredOutputReminder(agent.getStructuredOutputReminder());
            b.enablePendingToolRecovery(agent.isPendingToolRecoveryEnabled());
            b.checkRunning(agent.isCheckRunning());

            // Permission context (same instance — carries rules registered on the source).
            PermissionContextState srcPerm = agent.getPermissionContext();
            if (srcPerm != null) {
                b.permissionContext(srcPerm);
            }

            // Extension chains. Middlewares are the v2 surface; hooks remain for v1 carry-over.
            List<MiddlewareBase> srcMiddlewares = agent.getMiddlewares();
            if (srcMiddlewares != null && !srcMiddlewares.isEmpty()) {
                b.middlewares(srcMiddlewares);
            }
            List<Hook> srcHooks = agent.getHooks();
            if (srcHooks != null && !srcHooks.isEmpty()) {
                b.hooks(srcHooks);
            }

            return b;
        }

        // ---- Forwarder setters (proxy to inner + mirror) ----

        public Builder name(String name) {
            this.name = name;
            inner.name(name);
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            inner.description(description);
            return this;
        }

        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            inner.sysPrompt(sysPrompt);
            return this;
        }

        public Builder checkRunning(boolean checkRunning) {
            this.checkRunning = checkRunning;
            inner.checkRunning(checkRunning);
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            inner.model(model);
            return this;
        }

        public Builder model(String modelId) {
            Model resolved = io.agentscope.core.model.ModelRegistry.resolve(modelId);
            this.model = resolved;
            inner.model(resolved);
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit != null ? toolkit : new Toolkit();
            // Don't push to inner yet — orchestration will register harness tools on this toolkit
            // and then push the final result via inner.toolkit(...) at build() time.
            return this;
        }

        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            inner.maxIters(maxIters);
            return this;
        }

        public Builder modelExecutionConfig(ExecutionConfig config) {
            this.modelExecutionConfig = config;
            inner.modelExecutionConfig(config);
            return this;
        }

        public Builder toolExecutionConfig(ExecutionConfig config) {
            this.toolExecutionConfig = config;
            inner.toolExecutionConfig(config);
            return this;
        }

        public Builder generateOptions(GenerateOptions options) {
            this.generateOptions = options;
            inner.generateOptions(options);
            return this;
        }

        public Builder structuredOutputReminder(StructuredOutputReminder reminder) {
            inner.structuredOutputReminder(reminder);
            return this;
        }

        public Builder hook(Hook hook) {
            if (hook != null) {
                hooks.add(hook);
                inner.hook(hook);
            }
            return this;
        }

        public Builder hooks(List<Hook> hooks) {
            if (hooks != null) {
                for (Hook h : hooks) {
                    hook(h);
                }
            }
            return this;
        }

        public Builder middleware(MiddlewareBase middleware) {
            inner.middleware(middleware);
            return this;
        }

        public Builder middlewares(List<? extends MiddlewareBase> middlewares) {
            inner.middlewares(middlewares);
            return this;
        }

        public Builder stateStore(AgentStateStore stateStore) {
            this.stateStoreOverride = stateStore;
            inner.stateStore(stateStore);
            return this;
        }

        public Builder defaultSessionId(String defaultSessionId) {
            inner.defaultSessionId(defaultSessionId);
            return this;
        }

        public Builder toolExecutionContext(ToolExecutionContext ctx) {
            inner.toolExecutionContext(ctx);
            return this;
        }

        public Builder enableMetaTool(boolean enableMetaTool) {
            inner.enableMetaTool(enableMetaTool);
            return this;
        }

        public Builder enablePendingToolRecovery(boolean enable) {
            inner.enablePendingToolRecovery(enable);
            return this;
        }

        public Builder enableTaskList() {
            inner.enableTaskList();
            return this;
        }

        public Builder enableTaskList(boolean enabled) {
            inner.enableTaskList(enabled);
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            inner.maxRetries(maxRetries);
            return this;
        }

        public Builder fallbackModel(Model fallbackModel) {
            inner.fallbackModel(fallbackModel);
            return this;
        }

        public Builder fallbackModel(String modelId) {
            inner.fallbackModel(modelId);
            return this;
        }

        public Builder stopOnReject(boolean stopOnReject) {
            inner.stopOnReject(stopOnReject);
            return this;
        }

        public Builder permissionContext(PermissionContextState permissionContext) {
            inner.permissionContext(permissionContext);
            return this;
        }

        // ---- Harness-only setters ----

        /**
         * Sets the stable identifier used as the agent's namespace key in the composite filesystem
         * (e.g. {@code [agents, <agentId>, users, <userId>, ...]}). When unset, {@link #build()}
         * falls back to {@link #name(String)} for the namespace key.
         */
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        /**
         * Adds a marketplace / external skill repository (e.g. {@code GitSkillRepository}).
         * Repositories compose additively with workspace skills.
         */
        public Builder skillRepository(AgentSkillRepository skillRepository) {
            if (skillRepository != null) {
                this.skillRepositories.add(skillRepository);
            }
            return this;
        }

        /**
         * Replaces the current marketplace repository list with the given collection.
         */
        public Builder skillRepositories(List<AgentSkillRepository> repositories) {
            this.skillRepositories.clear();
            if (repositories != null) {
                for (AgentSkillRepository repo : repositories) {
                    if (repo != null) {
                        this.skillRepositories.add(repo);
                    }
                }
            }
            return this;
        }

        /**
         * Configures a project-global skills directory layered below marketplace and workspace
         * skills (lowest precedence).
         */
        public Builder projectGlobalSkillsDir(Path projectGlobalSkillsDir) {
            this.projectGlobalSkillsDir = projectGlobalSkillsDir;
            return this;
        }

        /**
         * Sets the workspace directory. Pass {@code null} to use the default
         * {@code ${cwd}/.agentscope/workspace}.
         */
        public Builder workspace(Path workspace) {
            this.workspace = workspace;
            return this;
        }

        /**
         * Sets the workspace directory from a filesystem path string.
         */
        public Builder workspace(String path) {
            if (path == null) {
                this.workspace = null;
            } else {
                String trimmed = path.strip();
                if (trimmed.isEmpty()) {
                    throw new IllegalArgumentException("workspace path must not be blank");
                }
                this.workspace = Path.of(trimmed);
            }
            return this;
        }

        public Builder environmentMemory(String environmentMemory) {
            this.environmentMemory = environmentMemory;
            return this;
        }

        /** Escape hatch: sets a custom {@link AbstractFilesystem} implementation directly. */
        public Builder abstractFilesystem(AbstractFilesystem backend) {
            this.abstractFilesystem = backend;
            return this;
        }

        /** Configures Mode 2 — sandbox filesystem. */
        public Builder filesystem(SandboxFilesystemSpec spec) {
            this.sandboxFilesystemSpec = spec;
            return this;
        }

        /** Configures Mode 1 — composite (non-sandbox) filesystem. */
        public Builder filesystem(RemoteFilesystemSpec spec) {
            this.remoteFilesystemSpec = spec;
            return this;
        }

        /** Configures Mode 3 — local filesystem with shell. */
        public Builder filesystem(LocalFilesystemSpec spec) {
            this.localFilesystemSpec = spec;
            return this;
        }

        /** Enables the {@link CompactionMiddleware} with the given configuration. */
        public Builder compaction(CompactionConfig config) {
            this.compactionConfig = config;
            return this;
        }

        /**
         * Overrides the long-term memory pipeline configuration (flush + consolidation +
         * maintenance). When not called, {@link MemoryConfig#defaults()} is used and behaviour
         * matches the harness's historical defaults.
         *
         * <p>For the compaction (in-context summarization) pipeline, see
         * {@link #compaction(CompactionConfig)}.
         */
        public Builder memory(MemoryConfig config) {
            this.memoryConfig = config != null ? config : MemoryConfig.defaults();
            return this;
        }

        /** Enables {@link ToolResultEvictionMiddleware} with the given configuration. */
        public Builder toolResultEviction(ToolResultEvictionConfig config) {
            this.toolResultEvictionConfig = config;
            return this;
        }

        /** Programmatic override for {@code workspace/tools.json}. */
        public Builder toolsConfig(ToolsConfig toolsConfig) {
            this.toolsConfigOverride = toolsConfig;
            return this;
        }

        /** Adds a subagent declaration. */
        public Builder subagent(SubagentDeclaration declaration) {
            this.subagentDeclarations.add(declaration);
            return this;
        }

        public Builder subagents(List<SubagentDeclaration> declarations) {
            this.subagentDeclarations.addAll(declarations);
            return this;
        }

        /** Adds a fully custom subagent factory for a given agent id. */
        public Builder subagentFactory(String name, Function<String, Agent> factory) {
            this.customSubagentFactories.add(
                    new HarnessAgentBuilderSupport.SubagentFactoryEntry(name, factory));
            return this;
        }

        /** Sets a custom {@link TaskRepository} for background subagent execution. */
        public Builder taskRepository(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
            return this;
        }

        /** Injects an external subagent tool (typically {@code SessionsTool}). */
        public Builder externalSubagentTool(Object tool) {
            this.externalSubagentTool = tool;
            return this;
        }

        /** Sets a resolver for model name strings to {@link Model} instances for subagents. */
        public Builder modelResolver(Function<String, Model> resolver) {
            this.modelResolver = resolver;
            return this;
        }

        /**
         * Adds a custom context file (relative to workspace) loaded into the system prompt
         * alongside AGENTS.md, MEMORY.md, and KNOWLEDGE.md.
         */
        public Builder additionalContextFile(String relativePath) {
            if (relativePath != null && !relativePath.isBlank()) {
                this.additionalContextFiles.add(relativePath);
            }
            return this;
        }

        /** Sets the maximum token budget for workspace context. */
        public Builder maxContextTokens(int maxTokens) {
            this.maxContextTokens = maxTokens;
            return this;
        }

        /** Switches workspace context rendering between markdown (default) and legacy XML style. */
        public Builder useLegacyXmlWorkspaceContext(boolean enabled) {
            this.useLegacyXmlWorkspaceContext = enabled;
            return this;
        }

        /**
         * Enables or disables agent execution trace logging via {@link AgentTraceMiddleware}.
         * Default is {@code true}.
         */
        public Builder enableAgentTracingLog(boolean enabled) {
            this.agentTracingLogEnabled = enabled;
            return this;
        }

        /** Skips registration of {@link FilesystemTool}. */
        public Builder disableFilesystemTools() {
            this.disableFilesystemTools = true;
            return this;
        }

        /** Skips registration of {@link ShellExecuteTool}. */
        public Builder disableShellTool() {
            this.disableShellTool = true;
            return this;
        }

        /** Disables dynamic per-call skill loading from the workspace filesystem. */
        public Builder disableDynamicSkills() {
            this.disableDynamicSkills = true;
            return this;
        }

        /**
         * Skips registration of the default Layer-4 {@link
         * io.agentscope.harness.agent.skill.WorkspaceSkillRepository}. User-supplied repositories
         * and the workspace skills directory (Layer 3) are still composed; only the namespaced
         * AbstractFilesystem-backed source is omitted.
         */
        public Builder disableDefaultWorkspaceSkills() {
            this.disableDefaultWorkspaceSkills = true;
            return this;
        }

        /**
         * Enables the agent-callable {@code skill_manage} tool so the agent can create / edit /
         * patch / archive its own skills in the workspace, and upgrades the workspace skill
         * repository to a writable variant.
         */
        public Builder enableSkillManageTool(SkillManageConfig config) {
            this.skillManageToolEnabled = true;
            this.skillManageConfig = config != null ? config : SkillManageConfig.defaults();
            return this;
        }

        /** Shorthand for {@link #enableSkillManageTool(SkillManageConfig)} with default config. */
        public Builder enableSkillManageTool(boolean autoPromote) {
            return enableSkillManageTool(
                    SkillManageConfig.builder().autoPromote(autoPromote).build());
        }

        /**
         * Configures the runtime promotion gate + visibility filter chain (M4 of skill
         * self-learning loop).
         */
        public Builder enableSkillPromotionGate(
                SkillPromotionGate gate, SkillVisibilityFilter visibilityFilter) {
            this.promotionGate = gate;
            this.visibilityFilter = visibilityFilter;
            return this;
        }

        /** Sets the deployment environment label used by {@code EnvironmentFilter}. */
        public Builder environment(String env) {
            this.environment = env != null ? env : "prod";
            return this;
        }

        /** Enables the background skill curator (M5). Requires {@link #enableSkillManageTool}. */
        public Builder enableSkillCurator(SkillCuratorConfig config) {
            this.skillCuratorEnabled = true;
            this.skillCuratorConfig = config != null ? config : SkillCuratorConfig.defaults();
            return this;
        }

        /**
         * Enables plan mode (read-only design phase) with {@code plan_enter}/{@code plan_write}/
         * {@code plan_exit} tools and a {@code PlanModeMiddleware} that enforces read-only tools
         * while plan mode is active.
         */
        public Builder enablePlanMode() {
            return enablePlanMode(true);
        }

        public Builder enablePlanMode(boolean enabled) {
            this.planModeEnabled = enabled;
            return this;
        }

        public Builder planFileDirectory(String dir) {
            if (dir != null && !dir.isBlank()) {
                this.planFileDir = dir;
            }
            return this;
        }

        public Builder skillFilter(io.agentscope.core.skill.SkillFilter filter) {
            this.skillFilter = filter;
            return this;
        }

        public Builder skillsEnabled(boolean enabled) {
            this.skillFilter =
                    enabled
                            ? io.agentscope.core.skill.SkillFilter.all()
                            : io.agentscope.core.skill.SkillFilter.none();
            return this;
        }

        public Builder enableSkills(String... skillNames) {
            this.skillFilter = io.agentscope.core.skill.SkillFilter.only(skillNames);
            return this;
        }

        public Builder disableSkills(String... skillNames) {
            this.skillFilter = io.agentscope.core.skill.SkillFilter.except(skillNames);
            return this;
        }

        public Builder disableDynamicSubagents() {
            this.disableDynamicSubagents = true;
            return this;
        }

        public Builder disableMemoryTools() {
            this.disableMemoryTools = true;
            return this;
        }

        public Builder disableMemoryHooks() {
            this.disableMemoryHooks = true;
            return this;
        }

        /** No-op since 2.0; session persistence is owned by ReActAgent itself. */
        public Builder disableSessionPersistence() {
            this.disableSessionPersistence = true;
            return this;
        }

        public Builder disableWorkspaceContext() {
            this.disableWorkspaceContext = true;
            return this;
        }

        public Builder disableAtPathExpansion() {
            this.disableAtPathExpansion = true;
            return this;
        }

        public Builder disableSubagents() {
            this.disableSubagents = true;
            return this;
        }

        public Builder disableToolsConfig() {
            this.disableToolsConfig = true;
            return this;
        }

        /**
         * Marks this build as a leaf subagent (no nested subagent orchestration). Package-private
         * because only {@link HarnessAgentBuilderSupport} subagent factories should mark agents
         * as leaves.
         */
        Builder asLeafSubagent() {
            this.leafSubagent = true;
            return this;
        }

        /**
         * Builds the subagent entries (general-purpose + declared + custom factories) without
         * constructing the full agent. Useful for callers that need to extract subagent factories
         * up front (for example to mount them on a session router).
         */
        public List<SubagentEntry> buildSubagentEntries(Path resolvedWorkspace) {
            return HarnessAgentBuilderSupport.buildSubagentEntries(this, resolvedWorkspace, null);
        }

        public List<SubagentEntry> buildSubagentEntries(
                Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
            return HarnessAgentBuilderSupport.buildSubagentEntries(
                    this, resolvedWorkspace, sandboxFs);
        }

        public HarnessAgent build() {
            // Toolkit deep-copy: each agent gets its own toolkit so harness-registered tools and
            // user-registered tools never bleed across builds.
            Toolkit agentToolkit = this.toolkit.copy();

            // ---- Validation ----
            int specCount = 0;
            if (sandboxFilesystemSpec != null) specCount++;
            if (remoteFilesystemSpec != null) specCount++;
            if (localFilesystemSpec != null) specCount++;
            if (specCount > 1) {
                throw new IllegalStateException(
                        "At most one of sandboxFilesystemSpec, remoteFilesystemSpec,"
                                + " localFilesystemSpec may be configured");
            }
            if (abstractFilesystem != null && specCount > 0) {
                throw new IllegalStateException(
                        "abstractFilesystem() is an escape hatch and is mutually exclusive with"
                                + " filesystem(...) specs");
            }
            Path resolvedWorkspace =
                    workspace != null
                            ? workspace
                            : Paths.get(System.getProperty("user.dir"))
                                    .resolve(".agentscope/workspace");
            String resolvedAgentId =
                    agentId != null && !agentId.isBlank()
                            ? agentId
                            : (name != null && !name.isBlank() ? name : "ReActAgent");
            AgentStateStore effectiveSession = stateStoreOverride;
            NamespaceFactory nsFactory =
                    rc -> {
                        String uid = rc != null ? rc.getUserId() : null;
                        return (uid == null || uid.isBlank()) ? List.of() : List.of(uid);
                    };
            if (effectiveSession == null) {
                effectiveSession = new JsonFileAgentStateStore(defaultStateDir(resolvedAgentId));
                inner.stateStore(effectiveSession);
            }

            if (remoteFilesystemSpec != null && isLocalSession(effectiveSession)) {
                throw new IllegalStateException(
                        "filesystem(RemoteFilesystemSpec) is designed for distributed /"
                            + " multi-replica deployments, but the effective AgentStateStore is a"
                            + " local in-process implementation (JsonFileAgentStateStore /"
                            + " InMemoryAgentStateStore). Configure a distributed AgentStateStore"
                            + " backend (for example RedisAgentStateStore) via .stateStore(...).");
            }
            WorkspaceIndex workspaceIndex =
                    remoteFilesystemSpec != null ? WorkspaceIndex.open(resolvedWorkspace) : null;
            AbstractFilesystem filesystem =
                    HarnessAgentBuilderSupport.resolveFilesystem(
                            this, resolvedWorkspace, resolvedAgentId, workspaceIndex, nsFactory);

            // ---- Sandbox integration ----
            SandboxLifecycleMiddleware sandboxLifecycleMw = null;
            SandboxContext defaultSandboxContext = null;
            SandboxBackedFilesystem capturedSandboxFs = null;
            if (sandboxFilesystemSpec != null) {
                capturedSandboxFs = new SandboxBackedFilesystem();
                filesystem = capturedSandboxFs;

                defaultSandboxContext = sandboxFilesystemSpec.toSandboxContext(resolvedWorkspace);

                if (isLocalSession(effectiveSession)) {
                    log.warn(
                            "[harness] Sandbox mode is using a local AgentStateStore ({})."
                                    + " Sandbox state will not survive JVM restarts and cannot be"
                                    + " shared across instances. For production, configure a"
                                    + " distributed AgentStateStore via .stateStore(...).",
                            effectiveSession.getClass().getSimpleName());
                }

                SessionSandboxStateStore stateStore =
                        new SessionSandboxStateStore(effectiveSession, resolvedAgentId);
                SandboxExecutionGuard executionGuard =
                        sandboxFilesystemSpec.getExecutionGuard() != null
                                ? sandboxFilesystemSpec.getExecutionGuard()
                                : SandboxExecutionGuard.noop();
                SandboxManager sandboxManager =
                        new SandboxManager(
                                defaultSandboxContext.getClient(),
                                stateStore,
                                resolvedAgentId,
                                executionGuard);
                sandboxLifecycleMw =
                        new SandboxLifecycleMiddleware(sandboxManager, capturedSandboxFs);
            }
            WorkspaceManager wsManager =
                    new WorkspaceManager(resolvedWorkspace, filesystem, workspaceIndex, nsFactory);
            wsManager.validate();

            final AbstractFilesystem sharedFilesystemRef = filesystem;
            final Path capturedWorkspace = resolvedWorkspace;
            final WorkspaceIndex capturedIndex = workspaceIndex;
            BiFunction<String, String, WorkspaceManager> workspaceFactoryFn =
                    (uid, sid) -> {
                        RuntimeContext bakedRc =
                                HarnessAgentBuilderSupport.buildBakedRuntimeContext(uid, sid);
                        NamespaceFactory ctxNs =
                                rc -> (uid == null || uid.isBlank()) ? List.of() : List.of(uid);
                        AbstractFilesystem ctxFs =
                                new io.agentscope.harness.agent.filesystem.BakedContextFilesystem(
                                        sharedFilesystemRef, bakedRc);
                        return new WorkspaceManager(capturedWorkspace, ctxFs, capturedIndex, ctxNs);
                    };

            // ---- Middlewares ----
            if (sandboxLifecycleMw != null) {
                inner.middleware(sandboxLifecycleMw);
            }
            if (agentTracingLogEnabled) {
                inner.middleware(new AgentTraceMiddleware());
            }
            if (!disableWorkspaceContext) {
                WorkspaceContextMiddleware markdownMw =
                        new WorkspaceContextMiddleware(
                                wsManager,
                                name != null ? name : "ReActAgent",
                                environmentMemory,
                                maxContextTokens);
                markdownMw.setAdditionalContextFiles(additionalContextFiles);
                inner.middleware(markdownMw);
            }
            if (!disableAtPathExpansion) {
                inner.middleware(new AtPathExpansionMiddleware(wsManager));
            }
            if (model != null && !disableMemoryHooks) {
                String effectiveFlushPrompt =
                        memoryConfig.flushPrompt() != null
                                ? memoryConfig.flushPrompt()
                                : MemoryFlushManager.DEFAULT_FLUSH_PROMPT;
                inner.middleware(
                        new MemoryFlushMiddleware(
                                wsManager,
                                model,
                                effectiveFlushPrompt,
                                memoryConfig.flushTrigger()));

                String effectiveConsolidationPrompt =
                        memoryConfig.consolidationPrompt() != null
                                ? memoryConfig.consolidationPrompt()
                                : MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT;
                MemoryConsolidator consolidator =
                        new MemoryConsolidator(
                                wsManager,
                                model,
                                effectiveConsolidationPrompt,
                                memoryConfig.consolidationMaxTokens());
                inner.middleware(
                        new MemoryMaintenanceMiddleware(
                                wsManager,
                                consolidator,
                                memoryConfig.dailyFileRetentionDays(),
                                memoryConfig.sessionRetentionDays(),
                                memoryConfig.consolidationMinGap()));
            }
            CompactionMiddleware compactionHook = null;
            if (compactionConfig != null && model != null) {
                compactionHook = new CompactionMiddleware(wsManager, model, compactionConfig);
                inner.middleware(compactionHook);
            }
            if (toolResultEvictionConfig != null) {
                inner.middleware(
                        new ToolResultEvictionMiddleware(filesystem, toolResultEvictionConfig));
            }
            if (!leafSubagent && !disableSubagents && model != null) {
                if (filesystem != null && !disableDynamicSubagents) {
                    DynamicSubagentsMiddleware dynMw =
                            HarnessAgentBuilderSupport.buildDynamicSubagentsMiddleware(
                                    this, wsManager, resolvedWorkspace, capturedSandboxFs);
                    if (dynMw != null) {
                        inner.middleware(dynMw);
                        for (Object t : dynMw.getTools()) {
                            agentToolkit.registerTool(t);
                        }
                    }
                } else {
                    SubagentsMiddleware subagentsMw =
                            HarnessAgentBuilderSupport.buildSubagentsMiddleware(
                                    this, wsManager, resolvedWorkspace, capturedSandboxFs);
                    if (subagentsMw != null) {
                        inner.middleware(subagentsMw);
                        for (Object t : subagentsMw.getTools()) {
                            agentToolkit.registerTool(t);
                        }
                    }
                }
            }

            // ---- Toolkit (memory / filesystem / shell tools) ----
            if (!disableMemoryTools) {
                agentToolkit.registerTool(new MemorySearchTool(wsManager));
                agentToolkit.registerTool(new MemoryGetTool(wsManager));
                agentToolkit.registerTool(new SessionSearchTool(wsManager));
            }
            if (!disableFilesystemTools) {
                agentToolkit.registerTool(new FilesystemTool(filesystem));
            }
            if (!disableShellTool && filesystem instanceof AbstractSandboxFilesystem sandbox) {
                agentToolkit.registerTool(new ShellExecuteTool(sandbox));
            }

            // ---- Plan mode (read-only design phase) ----
            PlanModeManager planModeManager = null;
            if (planModeEnabled) {
                planModeManager = new PlanModeManager(wsManager, planFileDir);
                agentToolkit.registerTool(new PlanModeTools.PlanEnterTool(planModeManager));
                agentToolkit.registerTool(new PlanModeTools.PlanWriteTool(planModeManager));
                agentToolkit.registerTool(new PlanModeTools.PlanExitTool(planModeManager));
                final Toolkit roToolkit = agentToolkit;
                inner.middleware(
                        new io.agentscope.harness.agent.middleware.PlanModeMiddleware(
                                planModeManager,
                                toolName -> {
                                    AgentTool t = roToolkit.getTool(toolName);
                                    return t instanceof ToolBase tb && tb.isReadOnly();
                                }));
            }

            // ---- workspace/tools.json: MCP servers + allow/deny filter ----
            ToolsConfig resolvedToolsConfig = null;
            if (!disableToolsConfig) {
                if (toolsConfigOverride != null) {
                    resolvedToolsConfig = toolsConfigOverride;
                } else if (wsManager != null) {
                    resolvedToolsConfig = ToolsConfigLoader.load(wsManager).orElse(null);
                }
            }
            if (resolvedToolsConfig != null) {
                McpServerRegistrar.register(agentToolkit, resolvedToolsConfig.getMcpServers());
            }

            // ---- Skills ----
            final AtomicReference<ReActAgent> selfRef = new AtomicReference<>();
            Supplier<RuntimeContext> currentRcSupplier =
                    () -> {
                        ReActAgent self = selfRef.get();
                        RuntimeContext rc = self != null ? self.getRuntimeContext() : null;
                        return rc != null ? rc : RuntimeContext.empty();
                    };
            List<AgentSkillRepository> orderedSkillRepos =
                    HarnessAgentBuilderSupport.composeSkillRepositories(
                            this, wsManager, filesystem, currentRcSupplier);

            // ---- Skill self-learning M1: writable workspace skills + skill_manage tool ----
            SkillPromoter pendingSkillPromoter = null;
            SkillUsageStore pendingSkillUsageStore = null;
            SkillCurator pendingSkillCurator = null;
            SkillAuditLog pendingSkillAuditLog = null;
            if (skillManageToolEnabled && filesystem != null) {
                SkillManageConfig smConfig =
                        skillManageConfig != null
                                ? skillManageConfig
                                : SkillManageConfig.defaults();
                WorkspaceSkillRepository mainWritableRepo = null;
                for (int i = orderedSkillRepos.size() - 1; i >= 0; i--) {
                    AgentSkillRepository r = orderedSkillRepos.get(i);
                    // The default Layer-4 repo (composeSkillRepositories) is a read-only
                    // WorkspaceSkillRepository pointed at "skills". Replace it with a
                    // writable one pointed at the configured main dir so skill_manage can
                    // persist.
                    if (r instanceof WorkspaceSkillRepository wsr && !wsr.isWriteable()) {
                        mainWritableRepo =
                                new WorkspaceSkillRepository(
                                        filesystem,
                                        smConfig.mainDir(),
                                        currentRcSupplier,
                                        "workspace-writable");
                        orderedSkillRepos.set(i, mainWritableRepo);
                        break;
                    }
                }
                if (mainWritableRepo == null) {
                    mainWritableRepo =
                            new WorkspaceSkillRepository(
                                    filesystem,
                                    smConfig.mainDir(),
                                    currentRcSupplier,
                                    "workspace-writable");
                    orderedSkillRepos.add(mainWritableRepo);
                }
                WorkspaceSkillRepository draftsWritableRepo =
                        new WorkspaceSkillRepository(
                                filesystem,
                                smConfig.draftsDir(),
                                currentRcSupplier,
                                "workspace-drafts");
                SkillUsageStore usageStore = new SkillUsageStore(filesystem);
                SkillAuditLog auditLog = new SkillAuditLog(filesystem, wsManager);
                SkillManageTool skillManageTool =
                        new SkillManageTool(
                                mainWritableRepo,
                                draftsWritableRepo,
                                smConfig,
                                usageStore,
                                auditLog);
                pendingSkillAuditLog = auditLog;
                agentToolkit.registerAgentTool(skillManageTool);
                agentToolkit.registerAgentTool(new ProposeSkillTool(skillManageTool));
                inner.middleware(
                        new io.agentscope.harness.agent.middleware.SkillUsageMiddleware(
                                usageStore));

                pendingSkillPromoter =
                        new SkillPromoter(
                                draftsWritableRepo,
                                mainWritableRepo,
                                wsManager,
                                usageStore,
                                promotionGate != null ? promotionGate : new RejectAllGate(),
                                smConfig.draftsDir(),
                                smConfig.mainDir(),
                                auditLog);
                pendingSkillUsageStore = usageStore;

                if (skillCuratorEnabled) {
                    SkillCurator curator =
                            new SkillCurator(
                                    filesystem,
                                    usageStore,
                                    mainWritableRepo,
                                    skillCuratorConfig != null
                                            ? skillCuratorConfig
                                            : SkillCuratorConfig.defaults());
                    pendingSkillCurator = curator;
                    inner.middleware(
                            new io.agentscope.harness.agent.middleware.SkillCuratorMiddleware(
                                    curator));
                }
            }

            if (!orderedSkillRepos.isEmpty() && !disableDynamicSkills) {
                // Always opt out of core's auto-install; harness owns the skill middleware.
                inner.dynamicSkillsEnabled(false);

                io.agentscope.harness.agent.skill.runtime.MarketplaceStager stager =
                        resolvedWorkspace != null
                                ? new io.agentscope.harness.agent.skill.runtime.MarketplaceStager(
                                        resolvedWorkspace)
                                : null;

                io.agentscope.harness.agent.skill.runtime.ShellPathPolicy shellPolicy;
                if (disableShellTool) {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy.noShell();
                } else if (filesystem instanceof LocalFilesystemWithShell) {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy
                                    .localWithShell(resolvedWorkspace);
                } else if (filesystem instanceof OverlayFilesystem ov
                        && ov.getUpper() instanceof LocalFilesystemWithShell) {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy
                                    .localWithShell(resolvedWorkspace);
                } else if (filesystem instanceof SandboxBackedFilesystem) {
                    String wsPrefix =
                            defaultSandboxContext != null
                                            && defaultSandboxContext.getClientOptions() != null
                                    ? defaultSandboxContext.getClientOptions().getWorkspaceRoot()
                                    : io.agentscope.harness.agent.skill.runtime.ShellPathPolicy
                                            .SANDBOX_WORKSPACE_PREFIX;
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy.sandbox(
                                    wsPrefix);
                } else {
                    shellPolicy =
                            io.agentscope.harness.agent.skill.runtime.ShellPathPolicy.noShell();
                }

                inner.middleware(
                        new HarnessSkillMiddleware(
                                orderedSkillRepos,
                                agentToolkit,
                                skillFilter,
                                visibilityFilter,
                                stager,
                                shellPolicy));
            } else if (disableDynamicSkills) {
                // Suppress core's auto-install so the static SkillBox fallback (constructed
                // below by staticSkillBoxFromRepos) remains the only skill source.
                inner.dynamicSkillsEnabled(false);
            }

            // ---- Apply tools.json allow/deny filter ----
            if (resolvedToolsConfig != null) {
                ToolFilter.apply(agentToolkit, resolvedToolsConfig);
            }

            log.info(
                    "HarnessAgent '{}' built [workspace={}, backend={}, subagents={}]",
                    name,
                    resolvedWorkspace,
                    filesystem.getClass().getSimpleName(),
                    !leafSubagent && !disableSubagents && model != null);

            // ---- Build inner ReActAgent ----
            inner.toolkit(agentToolkit);
            ReActAgent delegate = inner.build();
            selfRef.set(delegate);

            return new HarnessAgent(
                    delegate,
                    wsManager,
                    workspaceFactoryFn,
                    workspaceIndex,
                    defaultSandboxContext,
                    compactionHook,
                    sandboxLifecycleMw,
                    orderedSkillRepos,
                    planModeManager,
                    pendingSkillPromoter,
                    pendingSkillUsageStore,
                    pendingSkillCurator,
                    pendingSkillAuditLog,
                    memoryConfig);
        }
    }
}
