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
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.state.StatePersistence;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.BakedContextFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.hook.AgentTraceHook;
import io.agentscope.harness.agent.hook.CompactionHook;
import io.agentscope.harness.agent.hook.DynamicSkillHook;
import io.agentscope.harness.agent.hook.DynamicSubagentsHook;
import io.agentscope.harness.agent.hook.MemoryFlushHook;
import io.agentscope.harness.agent.hook.MemoryMaintenanceHook;
import io.agentscope.harness.agent.hook.SandboxLifecycleHook;
import io.agentscope.harness.agent.hook.SessionPersistenceHook;
import io.agentscope.harness.agent.hook.SubagentsHook;
import io.agentscope.harness.agent.hook.SubagentsHook.SubagentEntry;
import io.agentscope.harness.agent.hook.ToolResultEvictionHook;
import io.agentscope.harness.agent.hook.WorkspaceContextHook;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxStateStore;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.session.WorkspaceSession;
import io.agentscope.harness.agent.skill.FilesystemBackedSkillRepository;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.NamespaceFactory;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.RemoteSubagentStub;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.subagent.task.DefaultTaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.MemoryGetTool;
import io.agentscope.harness.agent.tool.MemorySearchTool;
import io.agentscope.harness.agent.tool.SessionSearchTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.ToolFilter;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.tools.ToolsConfigLoader;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * HarnessAgent is the user-facing API that wraps {@link ReActAgent} with enhanced harness practices:
 *
 * <ul>
 *   <li>Workspace-based context loading (AGENTS.md, KNOWLEDGE.md)
 *   <li>Skill loading via optional {@link AgentSkillRepository}, else {@link FileSystemSkillRepository} on
 *       workspace/skills/
 *   <li>Subagent orchestration via task/task_output tools (sync + background)
 *   <li>Memory flush and message offload before context compression
 *   <li>Session environment initialization (OS, date, workspace info)
 *   <li>Pluggable file-system backend (local, sandbox, composite)
 *   <li>Memory search/get tools
 * </ul>
 *
 * <p>Advanced users can skip individual built-in tools or hooks via {@link HarnessAgent.Builder#disableFilesystemTools()},
 * {@link HarnessAgent.Builder#disableShellTool()}, {@link HarnessAgent.Builder#disableMemoryTools()},
 * {@link HarnessAgent.Builder#disableMemoryHooks()}, {@link HarnessAgent.Builder#disableSessionPersistence()},
 * {@link HarnessAgent.Builder#disableWorkspaceContext()}, and {@link HarnessAgent.Builder#disableSubagents()},
 * then register replacements on the {@link Toolkit} or {@link Hook} list.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("MyAgent")
 *     .model(model) // or .model("openai:gpt-5.5") via {@link ModelRegistry}
 *     .sysPrompt("You are a helpful assistant.")
 *     .workspace("/path/to/workspace")
 *     .build();
 *
 * Msg response = agent.call(
 *     Msg.userMsg("Hello!"),
 *     RuntimeContext.builder().sessionId("sess-1").build()
 * ).block();
 * }</pre>
 */
public class HarnessAgent implements Agent, StateModule, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgent.class);

    private final ReActAgent delegate;
    private final WorkspaceManager workspaceManager;
    private final CompactionHook compactionHook;
    private final Session defaultSession;
    private final SandboxContext defaultSandboxContext;
    private final List<AgentSkillRepository> skillRepositories;

    /**
     * SQLite-backed workspace index allocated during {@link Builder#build()} when the agent is
     * configured with a {@link io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec},
     * shared across the main {@link #workspaceManager} and any per-ctx views produced by
     * {@link #workspaceFor(String, String)}. Owned by this agent; released by {@link #close()}.
     * {@code null} when no index was created.
     */
    private final WorkspaceIndex ownedWorkspaceIndex;

    /**
     * Factory for ctx-bound {@link WorkspaceManager} views — see {@link #workspaceFor(String,
     * String)}. Captured at build time so per-call views can be produced without depending on
     * mutable shared state on the agent instance.
     */
    private final java.util.function.BiFunction<String, String, WorkspaceManager> workspaceFactory;

    /**
     * Factory for per-userId {@link WorkspaceSession} views. Used to bake the calling userId into
     * the {@link io.agentscope.harness.agent.store.NamespaceFactory} so that
     * {@link io.agentscope.core.session.JsonSession#getSessionDir} (which has no
     * {@link RuntimeContext} on the API surface) still produces a per-user path. Returns {@code
     * null} when the default session is not a {@link WorkspaceSession}; callers must fall back to
     * {@link #defaultSession}.
     */
    private final java.util.function.Function<String, Session> sessionFactory;

    private volatile RuntimeContext runtimeContext;

    private HarnessAgent(
            ReActAgent delegate,
            WorkspaceManager workspaceManager,
            CompactionHook compactionHook,
            Session defaultSession,
            SandboxContext defaultSandboxContext,
            List<AgentSkillRepository> skillRepositories,
            java.util.function.BiFunction<String, String, WorkspaceManager> workspaceFactory,
            java.util.function.Function<String, Session> sessionFactory,
            WorkspaceIndex ownedWorkspaceIndex) {
        this.delegate = delegate;
        this.workspaceManager = workspaceManager;
        this.compactionHook = compactionHook;
        this.defaultSession = defaultSession;
        this.defaultSandboxContext = defaultSandboxContext;
        this.skillRepositories =
                skillRepositories != null ? List.copyOf(skillRepositories) : List.of();
        this.workspaceFactory = workspaceFactory;
        this.sessionFactory = sessionFactory;
        this.ownedWorkspaceIndex = ownedWorkspaceIndex;
    }

    /**
     * Releases resources owned by this agent — currently the SQLite-backed
     * {@link WorkspaceIndex} created when {@code RemoteFilesystemSpec} is configured.
     *
     * <p>Required for tests using {@code @TempDir} on Windows: while the JDBC connection holds
     * a file handle on {@code .index/workspace.db}, Windows refuses to delete the temp directory
     * and JUnit fails extension cleanup. Calling close releases the handle.
     *
     * <p>After close, the agent and any {@link WorkspaceManager} views produced from it must
     * not be used.
     */
    @Override
    public void close() {
        if (ownedWorkspaceIndex != null) {
            ownedWorkspaceIndex.close();
        }
    }

    /** Calls the agent with a runtime context, which provides sessionId and other metadata. */
    public Mono<Msg> call(Msg msg, RuntimeContext ctx) {
        return call(List.of(msg), ctx);
    }

    /** Calls the agent with multiple messages and a runtime context. */
    public Mono<Msg> call(List<Msg> msgs, RuntimeContext ctx) {
        bindRuntimeContext(ctx);
        return delegate.call(msgs, coreForDelegate())
                .onErrorResume(
                        e -> {
                            if (isContextOverflowError(e)) {
                                return recoverFromOverflow(msgs);
                            }
                            return Mono.error(e);
                        });
    }

    /** Streams the agent response with a runtime context. */
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, RuntimeContext ctx) {
        bindRuntimeContext(ctx);
        return delegate.stream(msgs, options, coreForDelegate());
    }

    /** Streams with default {@link StreamOptions} and a runtime context. */
    public Flux<Event> stream(List<Msg> msgs, RuntimeContext ctx) {
        return stream(msgs, StreamOptions.defaults(), ctx);
    }

    /** Streams a single message with default {@link StreamOptions} and a runtime context. */
    public Flux<Event> stream(Msg msg, RuntimeContext ctx) {
        return stream(List.of(msg), ctx);
    }

    private RuntimeContext coreForDelegate() {
        return runtimeContext != null ? runtimeContext : RuntimeContext.empty();
    }

    private Mono<Msg> recoverFromOverflow(List<Msg> msgs) {
        if (compactionHook != null) {
            // Force a compaction of the current memory contents by lowering the trigger threshold
            // to 1 so that compactIfNeeded always fires.
            log.warn(
                    "Context overflow detected, triggering emergency compaction via"
                            + " CompactionHook");
            return forceCompactAndRetry(delegate.getMemory(), msgs);
        }
        return Mono.error(
                new RuntimeException(
                        "Context overflow: no compaction configured, unable to recover"));
    }

    private Mono<Msg> forceCompactAndRetry(Memory memory, List<Msg> msgs) {
        List<Msg> allMsgs = memory.getMessages();
        if (allMsgs.isEmpty()) {
            return Mono.error(
                    new RuntimeException("Context overflow: memory is empty, cannot compact"));
        }
        RuntimeContext ctx = this.runtimeContext;
        String agentId = delegate.getName();
        String sessionId =
                ctx != null && ctx.getSessionId() != null ? ctx.getSessionId() : "default";

        // Force trigger by using a config with threshold=1 (always compact)
        CompactionConfig forceConfig = CompactionConfig.builder().triggerMessages(1).build();
        MemoryFlushManager fm = new MemoryFlushManager(workspaceManager, delegate.getModel());
        ConversationCompactor compactor = new ConversationCompactor(delegate.getModel(), fm);

        return compactor
                .compactIfNeeded(coreRuntimeForRecovery(), allMsgs, forceConfig, agentId, sessionId)
                .flatMap(
                        opt -> {
                            if (opt.isPresent()) {
                                memory.clear();
                                for (Msg m : opt.get()) {
                                    memory.addMessage(m);
                                }
                                return delegate.call(msgs, coreRuntimeForRecovery());
                            }
                            return Mono.error(
                                    new RuntimeException(
                                            "Context overflow: emergency compaction yielded no"
                                                    + " result"));
                        });
    }

    private io.agentscope.core.agent.RuntimeContext coreRuntimeForRecovery() {
        return runtimeContext != null
                ? runtimeContext
                : io.agentscope.core.agent.RuntimeContext.empty();
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

    private void bindRuntimeContext(RuntimeContext ctx) {
        if (ctx == null) {
            this.runtimeContext = null;
            return;
        }
        RuntimeContext effective = ensureSessionDefaults(ctx);
        this.runtimeContext = effective;
        if (effective.getSession() != null && effective.getSessionKey() != null) {
            try {
                delegate.loadIfExists(effective.getSession(), effective.getSessionKey());
            } catch (Exception e) {
                log.warn("Failed to load session state: {}", e.getMessage());
            }
        }
    }

    /**
     * Fills in default Session and SessionKey when the caller didn't provide them.
     * Session defaults to the agent-level {@link #defaultSession} (JsonSession).
     * SessionKey defaults to {@code SimpleSessionKey.of(sessionId)} when sessionId is
     * available, or {@code SimpleSessionKey.of(agentName)} as a last resort.
     */
    private RuntimeContext ensureSessionDefaults(RuntimeContext ctx) {
        Session session = ctx.getSession();
        if (session == null) {
            // When the agent's default session is a WorkspaceSession (single-tenant local store),
            // produce a per-call view with the caller's userId baked into its NamespaceFactory so
            // session state lands under <workspace>/<userId>/agents/.../context/ instead of the
            // shared workspace root.
            String uid = ctx.getUserId();
            if (sessionFactory != null && uid != null && !uid.isBlank()) {
                Session perCall = sessionFactory.apply(uid);
                session = perCall != null ? perCall : defaultSession;
            } else {
                session = defaultSession;
            }
        }
        SessionKey sessionKey = ctx.getSessionKey();
        if (sessionKey == null) {
            String id = ctx.getSessionId();
            if (id != null && !id.isBlank()) {
                sessionKey = SimpleSessionKey.of(id);
            } else {
                sessionKey = SimpleSessionKey.of(delegate.getName());
            }
        }
        // Inject default sandbox context if the call doesn't provide one
        SandboxContext sandboxCtx =
                ctx.get(SandboxContext.class) != null
                        ? ctx.get(SandboxContext.class)
                        : defaultSandboxContext;

        if (session == ctx.getSession()
                && sessionKey == ctx.getSessionKey()
                && sandboxCtx == ctx.get(SandboxContext.class)) {
            return ctx;
        }
        return RuntimeContext.builder()
                .sessionId(ctx.getSessionId())
                .userId(ctx.getUserId())
                .session(session)
                .sessionKey(sessionKey)
                .putAll(ctx.getExtra())
                .put(SandboxContext.class, sandboxCtx)
                .build();
    }

    // ==================== Agent interface delegation ====================

    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return delegate.call(msgs);
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
        return delegate.call(msgs, structuredModel);
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return delegate.call(msgs, schema);
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return delegate.stream(msgs, options);
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return delegate.stream(msgs, options, structuredModel);
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return delegate.stream(msgs, options, schema);
    }

    @Override
    public Mono<Void> observe(Msg msg) {
        return delegate.observe(msg);
    }

    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        return delegate.observe(msgs);
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

    public ReActAgent getDelegate() {
        return delegate;
    }

    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    /**
     * Returns a {@link WorkspaceManager} view whose filesystem and namespace are bound to the
     * given {@code (userId, sessionId)} for the duration of the returned view's IO. Unlike
     * {@link #getWorkspaceManager()}, this does <strong>not</strong> mutate the shared {@link
     * RuntimeContext} state used by the chat path ({@link #call} / {@link #stream}) — so it is
     * safe to call concurrently from per-request controllers without racing with active chats or
     * other requests on the same agent.
     *
     * <p>Semantics by filesystem mode:
     *
     * <ul>
     *   <li><b>Remote (composite)</b> — a fresh composite filesystem is built whose per-route
     *       {@link io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem}s use the
     *       supplied {@code userId} / {@code sessionId} directly (no mutable reference). IO
     *       lands in {@code [agents, <agentId>, users, <userId>, <route>, ...]} per the
     *       configured {@link io.agentscope.harness.agent.IsolationScope}.
     *   <li><b>Local / Sandbox / Custom</b> — the existing shared filesystem is reused (these
     *       modes do not have a per-user race in their backend); only the workspace-relative
     *       namespace factory is rebound for the disk-fallback subtree.
     * </ul>
     *
     * <p>Pass {@code null} for either parameter to opt out of that dimension. The returned view
     * is lightweight and may be created per request; callers should not cache it across requests
     * with different users.
     */
    public WorkspaceManager workspaceFor(String userId, String sessionId) {
        if (workspaceFactory == null) {
            return workspaceManager;
        }
        return workspaceFactory.apply(userId, sessionId);
    }

    /**
     * Returns a per-user {@link Session} view with userId baked into its namespace.
     *
     * <p>This mirrors {@link #ensureSessionDefaults(RuntimeContext)} — when a
     * {@code sessionFactory} is present, a WorkspaceSession scoped to {@code userId} is
     * returned; otherwise falls back to the agent-level {@code defaultSession}.
     * Callers should use this to read/write session state outside of a call flow.
     */
    public Session sessionFor(String userId) {
        if (sessionFactory != null && userId != null && !userId.isBlank()) {
            Session perCall = sessionFactory.apply(userId);
            return perCall != null ? perCall : defaultSession;
        }
        return defaultSession;
    }

    /**
     * Returns the {@link CompactionHook} instance if compaction was configured, or {@code null}.
     * Exposed for testing to verify compaction mirroring in child agents.
     */
    public CompactionHook getCompactionHook() {
        return compactionHook;
    }

    public RuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    /**
     * Returns the ordered list of {@link AgentSkillRepository} instances bound to this agent.
     * The list reflects the four-layer composition (project-global → marketplace → workspace
     * shared → per-user namespace) computed at build time, in priority order from lowest to
     * highest. The returned list is immutable.
     */
    public List<AgentSkillRepository> getSkillRepositories() {
        return skillRepositories;
    }

    // ==================== StateModule delegation ====================

    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        delegate.saveTo(session, sessionKey);
    }

    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        delegate.loadFrom(session, sessionKey);
    }

    @Override
    public boolean loadIfExists(Session session, SessionKey sessionKey) {
        return delegate.loadIfExists(session, sessionKey);
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a {@link Builder} pre-populated with the observable properties of an existing
     * {@link ReActAgent}, making it easy to migrate to {@link HarnessAgent} with minimal changes.
     *
     * <p>The following properties are copied from {@code agent}:
     * <ul>
     *   <li>{@code name}, {@code description}, {@code sysPrompt}
     *   <li>{@code model}, {@code maxIters}, {@code generateOptions}
     *   <li>{@code planNotebook}
     *   <li>{@code toolkit} — a defensive copy; all custom tools are preserved, and
     *       HarnessAgent's built-in tools (filesystem, memory-search, etc.) are added on top unless
     *       disabled via {@link HarnessAgent.Builder#disableFilesystemTools()} and related {@code disable*}
     *       methods
     * </ul>
     *
     * <p>Properties that are intentionally <b>not</b> copied:
     * <ul>
     *   <li>{@code memory} — HarnessAgent always manages its own fresh in-memory conversation
     *       store backed by workspace persistence
     *   <li>hooks — already compiled into the existing agent and not accessible via public API;
     *       add new harness hooks via {@link Builder#hook(Hook)} if needed
     *   <li>long-term memory, RAG, statePersistence, structuredOutputReminder — not
     *       accessible via public API on a built agent; re-configure via the returned builder
     * </ul>
     *
     * <p>Example migration:
     * <pre>{@code
     * // Before
     * ReActAgent agent = ReActAgent.builder()
     *     .name("my-agent")
     *     .model(model)
     *     .toolkit(myToolkit)
     *     .build();
     *
     * // After — minimal change
     * HarnessAgent agent = HarnessAgent.from(existingReActAgent)
     *     .workspace("/my/workspace")
     *     .build();
     * }</pre>
     *
     * @param agent the existing {@link ReActAgent} to migrate; must not be null
     * @return a new {@link Builder} pre-populated with the agent's observable configuration
     */
    public static Builder from(ReActAgent agent) {
        Builder b = new Builder();
        b.name = agent.getName();
        b.description = agent.getDescription();
        b.sysPrompt = agent.getSysPrompt();
        b.model = agent.getModel();
        b.maxIters = agent.getMaxIters();
        b.generateOptions = agent.getGenerateOptions();
        b.planNotebook = agent.getPlanNotebook();
        // Defensive copy so HarnessAgent's build() does not mutate the original agent's toolkit
        b.toolkit = agent.getToolkit().copy();
        return b;
    }

    public static class Builder {

        // Core ReActAgent params
        private String name;
        private String agentId;
        private String description;
        private String sysPrompt;
        private Model model;
        private Toolkit toolkit = new Toolkit();
        private int maxIters = 15;
        private ExecutionConfig modelExecutionConfig;
        private ExecutionConfig toolExecutionConfig;
        private GenerateOptions generateOptions;
        private final List<Hook> hooks = new ArrayList<>();

        /**
         * Marketplace / external skill repositories layered between the project-global directory
         * and the workspace agent-shared directory. Empty by default. {@link
         * #skillRepository(AgentSkillRepository)} appends to this list.
         */
        private final List<AgentSkillRepository> skillRepositories = new ArrayList<>();

        /**
         * Optional project-global skills directory (lowest precedence in the composition).
         * When {@code null}, no project-global layer is added.
         */
        private Path projectGlobalSkillsDir;

        private ToolExecutionContext toolExecutionContext;

        // Long-term memory configuration
        private LongTermMemory longTermMemory;
        private LongTermMemoryMode longTermMemoryMode = LongTermMemoryMode.BOTH;
        private boolean longTermMemoryAsyncRecord = false;

        // Plan configuration
        private PlanNotebook planNotebook;

        // RAG configuration
        private final List<Knowledge> knowledgeBases = new ArrayList<>();
        private RAGMode ragMode = RAGMode.GENERIC;
        private RetrieveConfig retrieveConfig =
                RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build();

        // Additional delegate params
        private StatePersistence statePersistence;
        private StructuredOutputReminder structuredOutputReminder;
        private boolean enableMetaTool = false;
        private boolean enablePendingToolRecovery = false;
        private boolean checkRunning = true;

        // Harness-specific params
        private Path workspace;
        private String environmentMemory;
        private AbstractFilesystem abstractFilesystem;
        private Session session;
        private SandboxDistributedOptions sandboxDistributedOptions;

        /**
         * When {@code true}, this agent is a leaf worker (spawned subagent): it does not register
         * {@link SubagentsHook}, preventing recursive delegation. Main agents keep this {@code
         * false}.
         */
        private boolean leafSubagent = false;

        /**
         * When {@code true} (default), registers {@link AgentTraceHook} to log reasoning and tool
         * execution at INFO; set logger {@code io.agentscope.harness.agent.hook.AgentTraceHook} to
         * DEBUG for full args and results. When {@code false}, no trace hook is added.
         */
        private boolean agentTracingLogEnabled = true;

        /**
         * When non-null, enables {@link CompactionHook} with this configuration.
         * Set via {@link #compaction(CompactionConfig)}.
         */
        private CompactionConfig compactionConfig = null;

        /**
         * When non-null, enables {@link ToolResultEvictionHook} with this configuration.
         * Set via {@link #toolResultEviction(ToolResultEvictionConfig)}.
         */
        private ToolResultEvictionConfig toolResultEvictionConfig = null;

        private final List<SubagentDeclaration> subagentDeclarations = new ArrayList<>();
        private final List<SubagentFactoryEntry> customSubagentFactories = new ArrayList<>();
        private TaskRepository taskRepository;
        private Object externalSubagentTool;
        private Function<String, Model> modelResolver;
        private final List<String> additionalContextFiles = new ArrayList<>();
        private int maxContextTokens = 8000;
        private boolean useLegacyXmlWorkspaceContext = false;

        /** When {@code true}, {@link FilesystemTool} is not registered. */
        private boolean disableFilesystemTools = false;

        /** When {@code true}, {@link ShellExecuteTool} is not registered (sandbox / local-shell modes only). */
        private boolean disableShellTool = false;

        /**
         * When {@code true}, {@link MemorySearchTool}, {@link MemoryGetTool}, and {@link SessionSearchTool}
         * are not registered.
         */
        private boolean disableMemoryTools = false;

        /**
         * When {@code true}, {@link MemoryFlushHook} and {@link MemoryMaintenanceHook} are not registered.
         */
        private boolean disableMemoryHooks = false;

        /** When {@code true}, {@link SessionPersistenceHook} is not registered. */
        private boolean disableSessionPersistence = false;

        /** When {@code true}, {@link WorkspaceContextHook} is not registered. */
        private boolean disableWorkspaceContext = false;

        /**
         * When {@code true}, {@link SubagentsHook} is not registered on this agent. Spawned leaf
         * subagents omit this hook regardless.
         */
        private boolean disableSubagents = false;

        /**
         * When {@code true}, the dynamic skill hook ({@code DynamicSkillHook}) is not registered
         * even when a workspace filesystem is configured. The build falls back to the legacy
         * {@code SkillHook} path via {@code resolveSkillBox()}.
         */
        private boolean disableDynamicSkills = false;

        /**
         * When {@code true}, the dynamic subagents hook ({@code DynamicSubagentsHook}) is not
         * registered even when a workspace filesystem is configured. The build falls back to the
         * legacy {@link SubagentsHook} which scans subagent declarations once at build time.
         */
        private boolean disableDynamicSubagents = false;

        /**
         * When {@code true}, {@code workspace/tools.json} is not consulted at build time. MCP
         * servers and allow/deny lists from that file are skipped entirely; the toolkit keeps
         * exactly the built-ins registered programmatically.
         */
        private boolean disableToolsConfig = false;

        /**
         * Programmatic override for {@code workspace/tools.json}. When non-null, {@link
         * ToolsConfigLoader} is bypassed and this value is used directly. Useful for tests.
         */
        private ToolsConfig toolsConfigOverride;

        // Filesystem mode configuration (at most one of these three is set)
        private SandboxFilesystemSpec sandboxFilesystemSpec;
        private RemoteFilesystemSpec remoteFilesystemSpec;
        private LocalFilesystemSpec localFilesystemSpec;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the stable identifier used as the agent's namespace key in the composite filesystem
         * (e.g. {@code [agents, <agentId>, users, <userId>, ...]}). This is distinct from
         * {@link #name(String)}, which is the human-facing display name and may change without
         * rewriting any keys.
         *
         * <p>When unset, {@code build()} falls back to {@link #name(String)} for the namespace key,
         * preserving prior behavior. Callers that need rename-safe storage (e.g. multi-tenant
         * platforms whose agents have a stable catalog/URL id distinct from the display name)
         * should set this explicitly.
         */
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * Configures the model from a string id resolved via {@link ModelRegistry}: a named
         * registration ({@link ModelRegistry#register(String, Model)}) or a built-in pattern such
         * as {@code openai:gpt-5.5}, {@code dashscope:qwen-max}, {@code anthropic:claude-sonnet-4-5},
         * {@code gemini:gemini-2.0-flash}, or {@code ollama:llama3}. API keys for auto-created models
         * come from standard environment variables ({@code OPENAI_API_KEY}, {@code DASHSCOPE_API_KEY},
         * etc.).
         *
         * @param modelId registry id or {@code provider:model} string
         * @return this builder
         * @throws IllegalArgumentException if the id cannot be resolved
         */
        public Builder model(String modelId) {
            this.model = ModelRegistry.resolve(modelId);
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        public Builder modelExecutionConfig(ExecutionConfig config) {
            this.modelExecutionConfig = config;
            return this;
        }

        public Builder toolExecutionConfig(ExecutionConfig config) {
            this.toolExecutionConfig = config;
            return this;
        }

        public Builder generateOptions(GenerateOptions options) {
            this.generateOptions = options;
            return this;
        }

        public Builder hook(Hook hook) {
            this.hooks.add(hook);
            return this;
        }

        public Builder hooks(List<Hook> hooks) {
            this.hooks.addAll(hooks);
            return this;
        }

        /**
         * Adds a marketplace / external skill repository (e.g. {@code GitSkillRepository},
         * Nacos, HTTP). Repositories compose <em>additively</em> with workspace skills: the
         * default precedence is project-global → marketplace repos (in registration order) →
         * workspace agent-shared → per-user namespaced filesystem, with later layers
         * overriding earlier ones on name collisions. Call this method multiple times to add
         * multiple sources.
         */
        public Builder skillRepository(AgentSkillRepository skillRepository) {
            if (skillRepository != null) {
                this.skillRepositories.add(skillRepository);
            }
            return this;
        }

        /**
         * Replaces the current marketplace repository list with the given collection. Useful
         * for bulk configuration from an external config; equivalent to clearing the list and
         * calling {@link #skillRepository(AgentSkillRepository)} for each entry.
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
         * Configures a project-global skills directory layered <em>below</em> marketplace and
         * workspace skills (lowest precedence). Used to ship default skills shared across all
         * agents started from a project. Pass {@code null} to clear.
         */
        public Builder projectGlobalSkillsDir(Path projectGlobalSkillsDir) {
            this.projectGlobalSkillsDir = projectGlobalSkillsDir;
            return this;
        }

        public Builder toolExecutionContext(ToolExecutionContext ctx) {
            this.toolExecutionContext = ctx;
            return this;
        }

        /**
         * Adds a knowledge base for RAG (Retrieval-Augmented Generation) on the delegate
         * {@link ReActAgent}.
         *
         * @param knowledge the knowledge base to add
         * @return this builder instance
         */
        public Builder knowledge(Knowledge knowledge) {
            if (knowledge != null) {
                this.knowledgeBases.add(knowledge);
            }
            return this;
        }

        /**
         * Adds multiple knowledge bases for RAG (Retrieval-Augmented Generation) on the delegate
         * {@link ReActAgent}.
         *
         * @param knowledges the list of knowledge bases to add
         * @return this builder instance
         */
        public Builder knowledges(List<Knowledge> knowledges) {
            if (knowledges != null) {
                this.knowledgeBases.addAll(knowledges);
            }
            return this;
        }

        /**
         * Sets the RAG mode on the delegate {@link ReActAgent}.
         *
         * @param mode the RAG mode (GENERIC, AGENTIC, or NONE)
         * @return this builder instance
         */
        public Builder ragMode(RAGMode mode) {
            if (mode != null) {
                this.ragMode = mode;
            }
            return this;
        }

        /**
         * Sets the retrieve configuration for RAG on the delegate {@link ReActAgent}.
         *
         * @param config the retrieve configuration
         * @return this builder instance
         */
        public Builder retrieveConfig(RetrieveConfig config) {
            if (config != null) {
                this.retrieveConfig = config;
            }
            return this;
        }

        /**
         * Sets the {@link PlanNotebook} for plan-based task execution on the delegate
         * {@link ReActAgent}.
         *
         * <p>Plan management tools will be automatically registered to the toolkit and a hook
         * will be added to inject plan hints before each reasoning step.
         *
         * @param planNotebook the configured PlanNotebook instance
         * @return this builder instance
         */
        public Builder planNotebook(PlanNotebook planNotebook) {
            this.planNotebook = planNotebook;
            return this;
        }

        /**
         * Enables plan functionality with default configuration on the delegate
         * {@link ReActAgent}. Equivalent to {@code planNotebook(PlanNotebook.builder().build())}.
         *
         * @return this builder instance
         */
        public Builder enablePlan() {
            this.planNotebook = PlanNotebook.builder().build();
            return this;
        }

        /**
         * Sets the long-term memory for the delegate {@link ReActAgent}.
         *
         * @param longTermMemory the long-term memory implementation
         * @return this builder instance
         */
        public Builder longTermMemory(LongTermMemory longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        /**
         * Sets the long-term memory mode for the delegate {@link ReActAgent}.
         *
         * @param mode the long-term memory mode
         * @return this builder instance
         */
        public Builder longTermMemoryMode(LongTermMemoryMode mode) {
            if (mode != null) {
                this.longTermMemoryMode = mode;
            }
            return this;
        }

        /**
         * Sets whether long-term memory recording should be performed asynchronously on the
         * delegate {@link ReActAgent}.
         *
         * @param asyncRecord whether to record memories asynchronously
         * @return this builder instance
         */
        public Builder longTermMemoryAsyncRecord(boolean asyncRecord) {
            this.longTermMemoryAsyncRecord = asyncRecord;
            return this;
        }

        /**
         * Sets the state persistence configuration for the delegate {@link ReActAgent}.
         *
         * @param statePersistence the state persistence configuration
         * @return this builder instance
         */
        public Builder statePersistence(StatePersistence statePersistence) {
            this.statePersistence = statePersistence;
            return this;
        }

        /**
         * Sets the structured output enforcement mode for the delegate {@link ReActAgent}.
         *
         * @param reminder the structured output reminder mode
         * @return this builder instance
         */
        public Builder structuredOutputReminder(StructuredOutputReminder reminder) {
            this.structuredOutputReminder = reminder;
            return this;
        }

        /**
         * Enables or disables the meta-tool functionality for the delegate {@link ReActAgent}.
         *
         * @param enableMetaTool true to enable the meta-tool
         * @return this builder instance
         */
        public Builder enableMetaTool(boolean enableMetaTool) {
            this.enableMetaTool = enableMetaTool;
            return this;
        }

        /**
         * Enables or disables automatic recovery from orphaned pending tool calls on the delegate
         * {@link ReActAgent}.
         *
         * @param enable true to enable auto-recovery
         * @return this builder instance
         */
        public Builder enablePendingToolRecovery(boolean enable) {
            this.enablePendingToolRecovery = enable;
            return this;
        }

        /**
         * Enables or disables the concurrent-execution guard on the delegate
         * {@link ReActAgent}. Defaults to {@code true}.
         *
         * @param checkRunning true to enable the guard
         * @return this builder instance
         */
        public Builder checkRunning(boolean checkRunning) {
            this.checkRunning = checkRunning;
            return this;
        }

        /**
         * Sets the workspace directory. Pass {@code null} to use the default
         * {@code ${cwd}/.agentscope/workspace}.
         *
         * @see #workspace(String)
         */
        public Builder workspace(Path workspace) {
            this.workspace = workspace;
            return this;
        }

        /**
         * Sets the workspace directory from a filesystem path string (resolved with
         * {@link Path#of(String, String...)}). Equivalent to {@link #workspace(Path)} with
         * {@code Path.of(path.strip())}.
         *
         * <p>Pass {@code null} for the same default as {@link #workspace(Path)} with a {@code null}
         * argument. Blank or whitespace-only strings are rejected.
         *
         * @param path absolute or relative path string, or {@code null} for the default workspace
         */
        public Builder workspace(String path) {
            if (path == null) {
                this.workspace = null;
                return this;
            }
            String trimmed = path.strip();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("workspace path must not be blank");
            }
            this.workspace = Path.of(trimmed);
            return this;
        }

        public Builder environmentMemory(String environmentMemory) {
            this.environmentMemory = environmentMemory;
            return this;
        }

        /**
         * Escape hatch: sets a custom {@link AbstractFilesystem} implementation directly.
         *
         * <p>Prefer {@link #filesystem(LocalFilesystemSpec)}, {@link #filesystem(RemoteFilesystemSpec)}
         * or {@link #filesystem(SandboxFilesystemSpec)} unless you have a bespoke backend that is
         * not expressible via any of the declarative specs.
         */
        public Builder abstractFilesystem(AbstractFilesystem backend) {
            this.abstractFilesystem = backend;
            return this;
        }

        /**
         * Configures <b>Mode 2 — sandbox filesystem</b> mode: fully isolated workspace running in a
         * sandbox (for example Docker). Long-term memory extraction/read and shell execution are
         * all routed through the sandbox session. State can be persisted via snapshots and resumed
         * by the configured isolation scope.
         *
         * @param spec sandbox filesystem spec (for example Docker sandbox spec)
         * @return this builder
         */
        public Builder filesystem(SandboxFilesystemSpec spec) {
            this.sandboxFilesystemSpec = spec;
            return this;
        }

        /**
         * Configures <b>Mode 1 — composite (non-sandbox) filesystem</b> mode: a unified workspace
         * view that blends a local {@code LocalFilesystem} backend with a shared
         * {@code RemoteFilesystem} for distributed long-term memory. Shell execution is not
         * available in this mode — selected prefixes ({@code MEMORY.md}, {@code memory/},
         * {@code agents/.../sessions/}) are routed to the store to keep memory consistent across
         * replicas.
         */
        public Builder filesystem(RemoteFilesystemSpec spec) {
            this.remoteFilesystemSpec = spec;
            return this;
        }

        /**
         * Configures <b>Mode 3 — local filesystem with shell</b> mode: the agent workspace is a
         * plain local directory and shell commands execute on the host. Long-term memory is kept
         * on the same local disk. Use for single-process / single-replica deployments.
         */
        public Builder filesystem(LocalFilesystemSpec spec) {
            this.localFilesystemSpec = spec;
            return this;
        }

        /**
         * Enables or disables agent execution trace logging via {@link AgentTraceHook}.
         * Default is {@code true}.
         */
        public Builder enableAgentTracingLog(boolean enabled) {
            this.agentTracingLogEnabled = enabled;
            return this;
        }

        /**
         * Skips registration of {@link FilesystemTool} ({@code read_file}, {@code write_file}, etc.).
         * Use when supplying a custom filesystem tool or a stricter wrapper on the {@link Toolkit}.
         */
        public Builder disableFilesystemTools() {
            this.disableFilesystemTools = true;
            return this;
        }

        /**
         * Skips registration of {@link ShellExecuteTool}. Only applies when the resolved filesystem is an
         * {@link AbstractSandboxFilesystem} (sandbox mode or default local workspace with shell).
         */
        public Builder disableShellTool() {
            this.disableShellTool = true;
            return this;
        }

        /**
         * Disables dynamic per-call skill loading from the workspace filesystem. Forces the build
         * to use the legacy {@code resolveSkillBox()} path even when a workspace filesystem is
         * configured.
         */
        public Builder disableDynamicSkills() {
            this.disableDynamicSkills = true;
            return this;
        }

        /**
         * Disables dynamic per-call subagent reload from the workspace filesystem. Forces the
         * build to use the legacy {@link SubagentsHook} which materialises the entry list once at
         * build time.
         */
        public Builder disableDynamicSubagents() {
            this.disableDynamicSubagents = true;
            return this;
        }

        /**
         * Skips registration of {@link MemorySearchTool}, {@link MemoryGetTool}, and {@link SessionSearchTool}.
         */
        public Builder disableMemoryTools() {
            this.disableMemoryTools = true;
            return this;
        }

        /**
         * Skips registration of {@link MemoryFlushHook} and {@link MemoryMaintenanceHook} (workspace-backed
         * memory maintenance around model calls).
         */
        public Builder disableMemoryHooks() {
            this.disableMemoryHooks = true;
            return this;
        }

        /**
         * Skips registration of {@link SessionPersistenceHook}. Only use when you persist agent state
         * through another mechanism.
         */
        public Builder disableSessionPersistence() {
            this.disableSessionPersistence = true;
            return this;
        }

        /**
         * Skips registration of {@link WorkspaceContextHook}, so AGENTS.md / workspace context is not
         * injected into the system message.
         */
        public Builder disableWorkspaceContext() {
            this.disableWorkspaceContext = true;
            return this;
        }

        /**
         * Skips registration of {@link SubagentsHook} on this agent (no {@code agent_spawn} / task tools
         * from harness subagent orchestration).
         */
        public Builder disableSubagents() {
            this.disableSubagents = true;
            return this;
        }

        /**
         * Skips reading {@code workspace/tools.json}. No MCP servers from that file are
         * registered, and allow/deny filtering is not applied. Programmatic {@code disable*}
         * switches still take effect.
         */
        public Builder disableToolsConfig() {
            this.disableToolsConfig = true;
            return this;
        }

        /**
         * Provides an in-memory {@link ToolsConfig} that bypasses the {@code workspace/tools.json}
         * file lookup. Setting a non-null value implies the same MCP-server registration and
         * allow/deny filtering steps as if this object were parsed from the workspace.
         */
        public Builder toolsConfig(ToolsConfig toolsConfig) {
            this.toolsConfigOverride = toolsConfig;
            return this;
        }

        /**
         * Enables the {@link CompactionHook} with the given configuration as the conversation
         * compaction strategy.
         *
         * <p>Use {@link CompactionConfig#builder()} to configure trigger thresholds, the keep
         * policy, and whether to flush/offload before summarisation.
         */
        public Builder compaction(CompactionConfig config) {
            this.compactionConfig = config;
            return this;
        }

        /**
         * Enables {@link ToolResultEvictionHook} with the given configuration.
         *
         * <p>When active, any tool result whose text content exceeds
         * {@link ToolResultEvictionConfig#getMaxResultChars()} is written to the
         * {@link AbstractFilesystem} and replaced with a compact placeholder in-context.
         * Use {@link ToolResultEvictionConfig#defaults()} for sensible out-of-the-box settings.
         *
         * <p>This mechanism is independent of conversation compaction: eviction addresses
         * individual oversized results (context width), while compaction addresses accumulated
         * conversation length (context depth).
         */
        public Builder toolResultEviction(ToolResultEvictionConfig config) {
            this.toolResultEvictionConfig = config;
            return this;
        }

        /**
         * Sets the default {@link Session} used for state persistence when
         * {@link RuntimeContext} does not provide one. When not set, defaults to a
         * {@link JsonSession} stored under {@code <workspace>/../sessions/}.
         */
        public Builder session(Session session) {
            this.session = session;
            return this;
        }

        /**
         * Enables high-level distributed sandbox configuration.
         *
         * <p>Bundles distributed concerns that pair with {@link #filesystem(SandboxFilesystemSpec)}:
         *
         * <ul>
         *   <li>distributed {@link Session} for sandbox state slots
         *   <li>optional {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec}
         *       override for workspace archive persistence
         *   <li>{@code requireDistributed} gate for fail-fast validation
         * </ul>
         *
         * <p>Configure {@link IsolationScope} on the {@code SandboxFilesystemSpec} only.
         *
         * <p>Requires sandbox mode (i.e. {@link #filesystem(SandboxFilesystemSpec)}).
         */
        public Builder sandboxDistributed(SandboxDistributedOptions options) {
            this.sandboxDistributedOptions = options;
            return this;
        }

        /**
         * Adds a subagent declaration (programmatic; workspace declarations come from
         * {@code subagents/*.md}).
         */
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
            this.customSubagentFactories.add(new SubagentFactoryEntry(name, factory));
            return this;
        }

        /** Sets a custom TaskRepository for background subagent execution. */
        public Builder taskRepository(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
            return this;
        }

        /**
         * Adds a custom context file (relative to workspace) that will be loaded into
         * the system prompt alongside AGENTS.md, MEMORY.md, and KNOWLEDGE.md.
         * Useful for files like SOUL.md, PREFERENCE.md, etc.
         *
         * @param relativePath workspace-relative path (e.g., "SOUL.md")
         * @return this builder instance
         */
        public Builder additionalContextFile(String relativePath) {
            if (relativePath != null && !relativePath.isBlank()) {
                this.additionalContextFiles.add(relativePath);
            }
            return this;
        }

        /**
         * Sets the maximum token budget for workspace context injected into the system prompt.
         *
         * @param maxTokens maximum tokens (default: 8000)
         * @return this builder instance
         */
        public Builder maxContextTokens(int maxTokens) {
            this.maxContextTokens = maxTokens;
            return this;
        }

        /**
         * Injects an external subagent tool (typically {@code SessionsTool}) to replace the
         * default {@code AgentTool}. Used by {@code AgentBootstrap} for session-mode orchestration.
         */
        public Builder externalSubagentTool(Object tool) {
            this.externalSubagentTool = tool;
            return this;
        }

        /**
         * Sets a resolver for model name strings to {@link Model} instances. Used when spec-based
         * subagents specify a {@code model} override (e.g. {@code "openai:gpt-4o-mini"}). When unset,
         * {@link ModelRegistry#resolve(String)} is used so subagent specs can use the same string ids
         * as {@link #model(String)}.
         */
        public Builder modelResolver(Function<String, Model> resolver) {
            this.modelResolver = resolver;
            return this;
        }

        /**
         * Switches workspace context rendering between markdown (default) and legacy XML
         * {@code <loaded_context>} style.
         */
        public Builder useLegacyXmlWorkspaceContext(boolean enabled) {
            this.useLegacyXmlWorkspaceContext = enabled;
            return this;
        }

        public List<SubagentEntry> buildSubagentEntries(Path resolvedWorkspace) {
            return buildSubagentEntries(resolvedWorkspace, null);
        }

        /**
         * Builds the subagent entries from programmatic declarations,
         * {@code workspace/subagents/*.md}, and custom factories. Useful for callers (e.g.
         * {@code AgentBootstrap}) that need to extract agent factories before building the full
         * agent.
         */
        public List<SubagentEntry> buildSubagentEntries(
                Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
            List<SubagentDeclaration> allDeclarations = new ArrayList<>(subagentDeclarations);

            Path subagentsDir = resolvedWorkspace.resolve("subagents");
            if (Files.isDirectory(subagentsDir)) {
                allDeclarations.addAll(
                        AgentSpecLoader.loadFromDirectory(subagentsDir, resolvedWorkspace));
            }

            List<SubagentEntry> entries = new ArrayList<>();

            entries.add(
                    new SubagentEntry(
                            "general-purpose",
                            "General-purpose subagent with same capabilities as the main agent."
                                    + " Use for any isolated task that can be fully delegated.",
                            buildGeneralPurposeFactory(resolvedWorkspace, sandboxFs),
                            null));

            for (SubagentDeclaration decl : allDeclarations) {
                entries.add(
                        new SubagentEntry(
                                decl.getName(),
                                decl.getDescription(),
                                buildDeclaredFactory(decl, resolvedWorkspace, sandboxFs),
                                decl));
            }

            for (SubagentFactoryEntry custom : customSubagentFactories) {
                entries.add(
                        new SubagentEntry(
                                custom.name(),
                                custom.name(),
                                () -> custom.factory().apply(custom.name()),
                                null));
            }

            return entries;
        }

        public HarnessAgent build() {
            // 文件系统四种模式互斥校验：
            //   模式1: filesystem(SandboxFilesystemSpec)   — 沙箱隔离（如 Docker）
            //   模式2: filesystem(RemoteFilesystemSpec)    — 本地+远程存储混合
            //   模式3: filesystem(LocalFilesystemSpec)     — 纯本地目录+本机 shell
            //   逃生口: abstractFilesystem(AbstractFilesystem) — 用户完全自定义后端

            // 校验1: 三种声明式 spec 只能选一个
            int specCount = 0;
            if (sandboxFilesystemSpec != null) specCount++;
            if (remoteFilesystemSpec != null) specCount++;
            if (localFilesystemSpec != null) specCount++;
            if (specCount > 1) {
                throw new IllegalStateException(
                        "At most one of sandboxFilesystemSpec, remoteFilesystemSpec,"
                                + " localFilesystemSpec may be configured");
            }

            // 校验2: 逃生口和声明式 spec 互斥
            if (abstractFilesystem != null && specCount > 0) {
                throw new IllegalStateException(
                        "abstractFilesystem() is an escape hatch and is mutually exclusive with"
                                + " filesystem(...) specs");
            }

            // 校验3: sandboxDistributed 只能在沙箱模式下使用
            if (sandboxDistributedOptions != null && sandboxFilesystemSpec == null) {
                throw new IllegalStateException(
                        "sandboxDistributed(...) requires sandbox mode."
                                + " Configure filesystem(SandboxFilesystemSpec) first.");
            }
            Path resolvedWorkspace =
                    workspace != null
                            ? workspace
                            : Paths.get(System.getProperty("user.dir"))
                                    .resolve(".agentscope/workspace");
            String resolvedAgentId =
                    agentId != null && !agentId.isBlank()
                            ? agentId
                            : (name != null && !name.isBlank() ? name : "HarnessAgent");
            Session effectiveSession =
                    sandboxDistributedOptions != null
                                    && sandboxDistributedOptions.getSession() != null
                            ? sandboxDistributedOptions.getSession()
                            : session;
            // RC-driven NamespaceFactory: every store operation passes its current
            // RuntimeContext, and the namespace is derived from rc.getUserId() at call time.
            // No shared mutable state.
            NamespaceFactory nsFactory =
                    rc -> {
                        String uid = rc != null ? rc.getUserId() : null;
                        return (uid == null || uid.isBlank()) ? List.of() : List.of(uid);
                    };
            if (effectiveSession == null) {
                effectiveSession =
                        new WorkspaceSession(resolvedWorkspace, resolvedAgentId, nsFactory);
            }

            // Mode 1 (RemoteFilesystemSpec) is inherently distributed: automatically require a
            // distributed Session so that conversation state is also shared across replicas.
            if (remoteFilesystemSpec != null && effectiveSession instanceof WorkspaceSession) {
                throw new IllegalStateException(
                        "filesystem(RemoteFilesystemSpec) is designed for distributed /"
                                + " multi-replica deployments, but the effective Session is a local"
                                + " WorkspaceSession. Configure a distributed Session backend (for"
                                + " example RedisSession) via .session(...).");
            }
            WorkspaceIndex workspaceIndex =
                    remoteFilesystemSpec != null ? WorkspaceIndex.open(resolvedWorkspace) : null;
            AbstractFilesystem filesystem =
                    resolveFilesystem(
                            resolvedWorkspace, resolvedAgentId, workspaceIndex, nsFactory);

            // ---- Sandbox integration ----
            SandboxLifecycleHook sandboxLifecycleHook = null;
            SandboxContext defaultSandboxContext = null;
            SandboxBackedFilesystem capturedSandboxFs = null;
            if (sandboxFilesystemSpec != null) {
                if (sandboxDistributedOptions != null
                        && sandboxDistributedOptions.getSnapshotSpec() != null) {
                    sandboxFilesystemSpec.snapshotSpec(sandboxDistributedOptions.getSnapshotSpec());
                }
                capturedSandboxFs = new SandboxBackedFilesystem();
                filesystem = capturedSandboxFs;

                defaultSandboxContext = sandboxFilesystemSpec.toSandboxContext(resolvedWorkspace);
                // Mode 2 (SandboxFilesystemSpec) always validates distributed prerequisites unless
                // the caller explicitly opts out via sandboxDistributed(requireDistributed=false).
                boolean skipDistributedValidation =
                        sandboxDistributedOptions != null
                                && !sandboxDistributedOptions.isRequireDistributed();
                if (!skipDistributedValidation) {
                    validateDistributedSandboxConfig(effectiveSession, defaultSandboxContext);
                }

                // SessionSandboxStateStore.slotKey already encodes per-scope discriminators
                // (sandbox/session/<id>, sandbox/user/<agentId>/<userId>, sandbox/agent/<agentId>,
                // sandbox/global). When the effective Session is a WorkspaceSession with a
                // userId-scoped namespace, layering that namespace on top would partition
                // AGENT/GLOBAL state per user and break their cross-caller sharing contract.
                Session sandboxStateSession =
                        effectiveSession instanceof WorkspaceSession
                                ? new WorkspaceSession(resolvedWorkspace, resolvedAgentId, null)
                                : effectiveSession;
                SandboxStateStore stateStore =
                        sandboxFilesystemSpec.getSandboxStateStore() != null
                                ? sandboxFilesystemSpec.getSandboxStateStore()
                                : new SessionSandboxStateStore(
                                        sandboxStateSession, resolvedAgentId);
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
                sandboxLifecycleHook = new SandboxLifecycleHook(sandboxManager, capturedSandboxFs);
            }
            WorkspaceManager wsManager =
                    new WorkspaceManager(resolvedWorkspace, filesystem, workspaceIndex, nsFactory);
            wsManager.validate();

            // ============================================================
            // workspaceFactoryFn: 按 (userId, sessionId) 创建用户维度的 WorkspaceManager
            // ============================================================
            // 作用：外部调用方（Controller、API 层）不需要走完整的 chat 调用链路就能拿到
            // 一个绑定了特定用户身份的 WorkspaceManager，实现多租户数据隔离。
            //
            // 工作原理：
            //   1. 将 (uid, sid) 烘焙进 RuntimeContext
            //   2. 用 BakedContextFilesystem 包装共享的 filesystem——后续每次文件操作都
            //      自动带上这个烘焙的身份，即使调用方传 RuntimeContext.empty() 也不影响
            //   3. NamespaceFactory 按 uid 生成命名空间列表 [uid]（如 ["alice"]），
            //      这样文件操作就落在 workspace/alice/ 子目录下
            //
            // 入口：HarnessAgent.workspaceFor(userId, sessionId)
            //
            // 示例：
            //   WorkspaceManager aliceWs = agent.workspaceFor("alice", "s1");
            //   String agentsMd = aliceWs.readAgentsMd(RuntimeContext.empty());
            //   // ↑ 实际读取的是 workspace/alice/AGENTS.md（如果 fs 有 per-user ns）
            final AbstractFilesystem sharedFilesystemRef = filesystem;
            final Path capturedWorkspace = resolvedWorkspace;
            final WorkspaceIndex capturedIndex = workspaceIndex;
            java.util.function.BiFunction<String, String, WorkspaceManager> workspaceFactoryFn =
                    (uid, sid) -> {
                        // 把 (uid, sid) 固定进 RuntimeContext，让后续文件操作无需再传身份
                        RuntimeContext bakedRc = buildBakedRuntimeContext(uid, sid);
                        // NamespaceFactory 按 userId 生成命名空间前缀
                        NamespaceFactory ctxNs =
                                rc -> (uid == null || uid.isBlank()) ? List.of() : List.of(uid);
                        // 包装共享 filesystem：每次调用都用 bakedRc 替换传进来的 RuntimeContext
                        AbstractFilesystem ctxFs =
                                new BakedContextFilesystem(sharedFilesystemRef, bakedRc);
                        return new WorkspaceManager(capturedWorkspace, ctxFs, capturedIndex, ctxNs);
                    };

            // ============================================================
            // sessionFactoryFn: 按 userId 创建用户维度的 Session
            // ============================================================
            // 作用：为每个用户创建独立的 WorkspaceSession，使会话状态按 userId 隔离。
            //
            // 条件：只在 defaultSession 是 WorkspaceSession（纯本地模式）时才生效。
            //       如果用户配置了分布式 Session（如 RedisSession），这里返回 null，
            //       调用方回退到 defaultSession——分布式 Session 自己管理隔离。
            //
            // 示例：
            //   Session aliceSession = sessionFactory.apply("alice");
            //   // aliceSession 数据落在 workspace/agents/<agentId>/sessions/ 下
            //   // 且 NamespaceFactory 固定返回 ["alice"]，与 bob 的数据隔离
            final Session capturedDefaultSession = effectiveSession;
            final String capturedAgentId = resolvedAgentId;
            java.util.function.Function<String, Session> sessionFactoryFn =
                    capturedDefaultSession instanceof WorkspaceSession
                            ? uid -> {
                                NamespaceFactory baked = rc -> List.of(uid);
                                return new WorkspaceSession(
                                        capturedWorkspace, capturedAgentId, baked);
                            }
                            : null;

            Memory memory = new InMemoryMemory();

            // ---- Hooks 装配：在 ReAct 循环的关键时机注入能力 ----
            //
            // Hook 按 priority 排序后注入 ReActAgent，在每个推理→行动→结果的周期中拦截：
            //   1. PreReasoning（推理前） — 注入上下文、压缩历史、恢复状态
            //   2. PostReasoning（推理后）
            //   3. PreActing（工具执行前）— 重写工具参数
            //   4. PostActing（工具执行后）— 卸载大结果
            //   5. PostCall（调用结束后）— 刷新记忆、持久化状态
            List<Hook> allHooks = new ArrayList<>(hooks);

            // SandboxLifecycleHook: 沙箱生命周期管理（priority=50，最先执行）
            //   场景：沙箱模式下，每次调用需要获取/释放沙箱 session
            //   效果：call() 开始时自动获取沙箱 session，结束时自动释放
            if (sandboxLifecycleHook != null) {
                allHooks.add(sandboxLifecycleHook);
            }

            // AgentTraceHook: 追踪日志（priority=100）
            //   场景：开发调试 / 生产监控
            //   效果：INFO 级别记录每次推理和工具调用，DEBUG 级别记录完整参数和结果
            if (agentTracingLogEnabled) {
                allHooks.add(new AgentTraceHook());
            }

            // ============================================================
            // WorkspaceContextHook: 工作区上下文注入（PreCall 阶段，priority 900）
            // ============================================================
            //   场景：agent 每次 call() 前需要知道自己是谁、记住什么、懂什么
            //   效果：在 PreCallEvent 时拼装完整系统提示词，追加到用户原始 sysPrompt 之后：
            //     - Session Context     → 日期/OS/工作区路径/Session ID
            //     - 行为指引            → 如何使用 knowledge/、memory_search、
            //                            MEMORY.md 何时更新
            //     - AGENTS.md           → agent 人格与行为约定
            //     - MEMORY.md           → 跨会话长期记忆（用户偏好、关键事实），
            //                            token 超限时优先截断此部分
            //     - KNOWLEDGE.md        → 领域知识概要 + knowledge/ 文件列表目录
            //     - 额外上下文文件       → setAdditionalContextFiles 指定的文件
            //   注意：日流水账（memory/YYYY-MM-DD.md）不在此注入，
            //         它仅由 MemoryConsolidator 读取用于合并
            //   示例：用户在 3 天前提过"我叫天宇"，MEMORY.md 通过 consolidateMemory
            //         记录了这个事实，agent 今天推理时仍能叫出用户名字
            if (!disableWorkspaceContext) {
                // priority 900 PreCallEvent
                WorkspaceContextHook markdownHook =
                        new WorkspaceContextHook(
                                wsManager,
                                name != null ? name : "HarnessAgent",
                                environmentMemory,
                                maxContextTokens);
                markdownHook.setAdditionalContextFiles(additionalContextFiles);
                allHooks.add(markdownHook);
            }

            // ============================================================
            // MemoryFlushHook: daily.md记忆追加新的memory entry（PostCall 阶段）
            // ============================================================
            //   场景：对话中出现了值得长期记住的事实（用户姓名、偏好、计划等），
            //         需要从对话历史中提炼并落到 memory/YYYY-MM-DD.md
            //   效果：每次 call() 结束后，用 LLM 扫描对话历史，提取关键事实，
            //         追加到当日记忆文件（日流水账）。这样即使上下文被压缩或
            //         进程重启，重要信息也不会丢失
            //   示例：用户说"我喜欢喝冰美式"，flush 后将此偏好写入 memory/2026-06-06.md，
            //         下周 agent 仍能通过读取 memory 文件回忆起这个偏好
            MemoryFlushHook memoryFlushHook = null;
            if (model != null && !disableMemoryHooks) {
                // priority 5 PostCallEvent
                memoryFlushHook = new MemoryFlushHook(wsManager, model);
                allHooks.add(memoryFlushHook);
            }

            // ============================================================
            // MemoryMaintenanceHook: memory.md记忆后台维护（PostCall 阶段顺带归档daily.md和清理*.log.jsonl，priority
            // 6）
            // ============================================================
            // 节流控制：默认每 30 分钟最多执行一次，非每次调用都触发
            // runMaintenance 内含 3 个动作（按顺序）：
            //
            // ① expireDailyFiles  — 清理过期的日流水账文件
            //    场景：memory/ 下 YYYY-MM-DD.md 越积越多
            //    效果：将 dailyFileRetentionDays（默认 90 天）之前的日流水账
            //          移动到 memory/archive/ 归档
            //    示例：今天是 2026-06-06，2026-03-01.md → memory/archive/2026-03-01.md
            //
            // ② consolidateMemory  — LLM 合并去重到 MEMORY.md
            //    场景：日流水账碎片化，同一事实散落在多天文件中
            //    效果：MemoryConsolidator 读取 watermark 之后的新日流水账 +
            //          当前 MEMORY.md，交给 LLM 去重/合并/归类后覆盖写入 MEMORY.md
            //    示例：3 天日流水账都记录了"用户叫天宇"，合并后 MEMORY.md 仅保留一条：
            //          "- 用户姓名：天宇"
            //
            // ③ pruneOldSessions  — 清理过期的 *.log.jsonl 会话日志
            //    场景：agents/ 下 .log.jsonl 完整对话日志越积越多
            //    效果：将 sessionRetentionDays（默认 180 天）之前修改的
            //          .log.jsonl 文件删除（.jsonl 上下文文件不受影响）
            //    示例：agents/*/sessions/2025-09-01.log.jsonl → 删除
            if (model != null && !disableMemoryHooks) {
                // priority 6 PostCallEvent
                MemoryConsolidator consolidator = new MemoryConsolidator(wsManager, model);
                allHooks.add(new MemoryMaintenanceHook(wsManager, consolidator));
            }

            // ============================================================
            // CompactionHook: 对话压缩（PreReasoning 阶段）
            // ============================================================
            //   场景：长对话中消息数达到阈值（如 30 条），模型上下文窗口快装不下了
            //   效果：用 LLM 对当前会话的历史消息做摘要压缩，保留最近 N 条（如 10 条）原始消息，其余替换为摘要文本。
            //         flushBeforeCompact=true → 压缩前先提取事实到日流水账，确保重要信息不因压缩而丢失
            //         offloadBeforeCompact=true → 压缩前先把原始消息归档到 JSONL session
            // tree，例如:agents/<agentId>/sessions/<sessionId>.jsonl
            //         完整历史仍可通过 SessionSearchTool 检索
            //   示例：30 轮对话 → 触发压缩 → 前 20 轮被摘要为一段文字，
            //         最近 10 轮保持原文，上下文从 30 条降到 ~11 条
            CompactionHook compactionHook = null;
            if (compactionConfig != null && model != null) {
                // priority 10 PreReasoningEvent
                compactionHook = new CompactionHook(wsManager, model, compactionConfig);
                allHooks.add(compactionHook);
            }

            // ============================================================
            // ToolResultEvictionHook: 大工具结果卸载（PostActing 阶段）
            // ============================================================
            //   场景：工具返回了超大结果（如 cat 了一个 2MB 的日志文件），
            //         直接塞回上下文会撑爆模型
            //   效果：超出阈值的工具结果被写入文件系统，上下文里只留占位符 +
            //         预览（前 N 行），agent 可调用 read_file 按需回读完整内容
            //   示例：agent 执行 shell_execute("cat /var/log/app.log") 返回 500KB，
            //         eviction 将其存入 workspace 文件，上下文中替换为：
            //         "Result written to /tmp/result-abc123.txt (500KB). Preview: [前 2KB]..."
            if (toolResultEvictionConfig != null) {
                allHooks.add(new ToolResultEvictionHook(filesystem, toolResultEvictionConfig));
            }

            // ============================================================
            // SessionPersistenceHook: 会话持久化（PostCall 阶段）
            // ============================================================
            //   场景：进程重启后 agent 需要记得之前聊到哪了
            //   效果：每次 call() 结束后，将 agent 的状态（对话历史、工具调用等）
            //         写入 Session（JSONL 文件或远端存储），下次相同 sessionId 的
            //         调用自动从断点恢复
            //   示例：用户昨天和 agent 聊到第 15 轮，今天用相同 sessionId 发起新对话，
            //         agent 自动加载昨天的 15 轮历史，无缝继续
            if (!disableSessionPersistence) {
                allHooks.add(new SessionPersistenceHook());
            }

            // ============================================================
            // SubagentsHook / DynamicSubagentsHook: 子 agent 编排
            // ============================================================
            //   场景：复杂任务需要分解——主 agent 调用子 agent 处理独立子任务
            //   效果：注入 task / task_output 工具，主 agent 可同步或后台委派子 agent。
            //         DynamicSubagentsHook 从远端 filesystem 动态发现子 agent 规格文件，
            //         SubagentsHook 只在 build 时一次性扫描 workspace 下的 subagents/*.md
            //
            //   跳过条件：
            //     - leafSubagent=true：当前就是子 agent，不再注册子 agent 能力（防止无限递归）
            //     - disableSubagents=true：显式禁用
            //
            //   示例：
            //     用户："帮我在 GitHub 上搜 Python 爬虫项目，并分析质量"
            //     主 agent → task("github-search", "搜索 Python 爬虫 TOP 5")
            //            → task("code-reviewer", "分析这 5 个项目的代码质量")
            //            → 汇总各子 agent 结果返回给用户
            if (!leafSubagent && !disableSubagents && model != null) {
                if (filesystem != null && !disableDynamicSubagents) {
                    DynamicSubagentsHook dynamicSubagentsHook =
                            buildDynamicSubagentsHook(
                                    wsManager, resolvedWorkspace, capturedSandboxFs);
                    if (dynamicSubagentsHook != null) {
                        allHooks.add(dynamicSubagentsHook);
                    }
                } else {
                    SubagentsHook subagentsHook =
                            buildSubagentsHook(wsManager, resolvedWorkspace, capturedSandboxFs);
                    if (subagentsHook != null) {
                        allHooks.add(subagentsHook);
                    }
                }
            }

            // ---- Toolkit 注册：给 agent 装配内置工具 ----
            Toolkit agentToolkit = toolkit;

            // MemorySearchTool / MemoryGetTool / SessionSearchTool: 记忆与会话检索
            //   场景：agent 需要查阅历史记忆或过往会话
            //   效果：agent 通过 tool call 搜索 workspace 中的记忆文件和会话记录
            //   示例：用户问"我之前说过的那个项目叫什么？"
            //         agent 调用 memory_search("项目") → 找到 MEMORY.md 中的记录
            if (!disableMemoryTools) {
                agentToolkit.registerTool(new MemorySearchTool(wsManager));
                agentToolkit.registerTool(new MemoryGetTool(wsManager));
                agentToolkit.registerTool(new SessionSearchTool(wsManager));
            }

            // FilesystemTool: 文件系统操作（read_file, write_file, edit_file, grep, glob, ls）
            //   场景：agent 需要读写 workspace 中的文件
            //   效果：注册 6 个文件操作工具，所有操作走 AbstractFilesystem（本地/远程/沙箱透明）
            if (!disableFilesystemTools) {
                agentToolkit.registerTool(new FilesystemTool(filesystem));
            }

            // ShellExecuteTool: shell 命令执行（仅沙箱或本地+shell 模式）
            //   场景：agent 需要执行 shell 命令（安装依赖、运行脚本、编译代码等）
            //   条件：filesystem 实现了 AbstractSandboxFilesystem
            //        （SandboxFilesystemSpec 或 LocalFilesystemWithShell）
            //         RemoteFilesystemSpec 模式没有 shell，因为远端存储无法执行命令
            if (!disableShellTool && filesystem instanceof AbstractSandboxFilesystem sandbox) {
                agentToolkit.registerTool(new ShellExecuteTool(sandbox));
            }

            // ---- tools.json: MCP 服务器注册 + allow/deny 过滤 ----
            //   场景：用户通过 workspace/tools.json 声明式配置 MCP 工具服务器和工具白名单/黑名单
            //   效果：先注册 MCP 工具 -> 注册静态 skill 工具 -> 最后 apply allow/deny 过滤
            //   优先级：toolsConfigOverride（编程式）> workspace/tools.json（声明式）
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

            // ---- Skills: 四层技能仓库合成 ----
            //   优先级（低→高，后者覆盖前者同名 skill）：
            //     1. 全局目录    (~/.agentscope/skills/)
            //     2. Marketplace 仓库
            //     3. 工作区共享   (workspace/skills/)
            //     4. 用户命名空间 (<userId>/skills/)
            //
            //   动态模式（默认）：DynamicSkillHook 每次推理时动态加载，支持运行时新增/修改 skill
            //   静态模式（disableDynamicSkills=true）：build 时一次性合并为 SkillBox，后续不变
            //
            //   示例：workspace/skills/ 有个 code-review SKILL.md，
            //         alice 也有私有的 code-review → alice 的版本覆盖 workspace 共享版
            final AtomicReference<HarnessAgent> selfRef = new AtomicReference<>();
            Supplier<RuntimeContext> currentRcSupplier =
                    () -> {
                        HarnessAgent self = selfRef.get();
                        return self != null && self.runtimeContext != null
                                ? self.runtimeContext
                                : RuntimeContext.empty();
                    };
            List<AgentSkillRepository> orderedSkillRepos =
                    composeSkillRepositories(wsManager, filesystem, currentRcSupplier);
            SkillBox effectiveSkillBox = null;
            if (!orderedSkillRepos.isEmpty()) {
                if (disableDynamicSkills) {
                    effectiveSkillBox = staticSkillBoxFromRepos(orderedSkillRepos, agentToolkit);
                } else {
                    allHooks.add(new DynamicSkillHook(orderedSkillRepos, agentToolkit));
                }
            }

            // ---- 应用 tools.json 的 allow/deny 过滤器（MCP + 静态 skill 注册之后） ----
            if (resolvedToolsConfig != null) {
                ToolFilter.apply(agentToolkit, resolvedToolsConfig);
            }

            // ---- 构建 ReActAgent delegate ----
            ReActAgent.Builder reactBuilder =
                    ReActAgent.builder()
                            .name(name)
                            .description(description)
                            .sysPrompt(sysPrompt)
                            .model(model)
                            .toolkit(agentToolkit)
                            .memory(memory)
                            .maxIters(maxIters)
                            .hooks(allHooks);

            if (modelExecutionConfig != null) {
                reactBuilder.modelExecutionConfig(modelExecutionConfig);
            }
            if (toolExecutionConfig != null) {
                reactBuilder.toolExecutionConfig(toolExecutionConfig);
            }
            if (generateOptions != null) {
                reactBuilder.generateOptions(generateOptions);
            }
            if (effectiveSkillBox != null) {
                reactBuilder.skillBox(effectiveSkillBox);
            }
            if (toolExecutionContext != null) {
                reactBuilder.toolExecutionContext(toolExecutionContext);
            }
            if (!knowledgeBases.isEmpty()) {
                reactBuilder
                        .knowledges(knowledgeBases)
                        .ragMode(ragMode)
                        .retrieveConfig(retrieveConfig);
            }
            if (planNotebook != null) {
                reactBuilder.planNotebook(planNotebook);
            }
            if (longTermMemory != null) {
                reactBuilder
                        .longTermMemory(longTermMemory)
                        .longTermMemoryMode(longTermMemoryMode)
                        .longTermMemoryAsyncRecord(longTermMemoryAsyncRecord);
            }
            if (statePersistence != null) {
                reactBuilder.statePersistence(statePersistence);
            }
            if (structuredOutputReminder != null) {
                reactBuilder.structuredOutputReminder(structuredOutputReminder);
            }
            reactBuilder
                    .enableMetaTool(enableMetaTool)
                    .enablePendingToolRecovery(enablePendingToolRecovery)
                    .checkRunning(checkRunning);

            ReActAgent delegate = reactBuilder.build();

            log.info(
                    "HarnessAgent '{}' built [workspace={}, backend={}, subagents={}]",
                    name,
                    resolvedWorkspace,
                    filesystem.getClass().getSimpleName(),
                    !leafSubagent && !disableSubagents && model != null);

            HarnessAgent harnessAgent =
                    new HarnessAgent(
                            delegate,
                            wsManager,
                            compactionHook,
                            effectiveSession,
                            defaultSandboxContext,
                            orderedSkillRepos,
                            workspaceFactoryFn,
                            sessionFactoryFn,
                            workspaceIndex);
            selfRef.set(harnessAgent);
            return harnessAgent;
        }

        // @formatter:off
        /**
         * Subagent context section injected into every subagent's system prompt.
         * Establishes identity, rules, output format, and prohibited behaviours for a leaf worker.
         * The task itself is delivered as the first user message, not duplicated here.
         */
        private static final String SUBAGENT_CONTEXT_SECTION =
                """
                # Subagent Context

                You are a **subagent** spawned by the main agent for a specific task.

                ## Your Role
                - Complete the assigned task. That's your entire purpose.
                - You are NOT the main agent. Don't try to be.

                ## Rules
                1. **Stay focused** — Do your assigned task, nothing else
                2. **Complete the task** — Your final message will be automatically reported to the main agent
                3. **Don't initiate** — No heartbeats, no proactive actions, no side quests
                4. **Be ephemeral** — You may be terminated after task completion. That's fine.
                5. **Recover from truncated tool output** — If you see `[truncated: output exceeded context limit]`, re-read only what you need using smaller chunks (read with offset/limit, or targeted grep/head/tail) instead of full re-reads

                ## Output Format
                When complete, your final response should include:
                - What you accomplished or found
                - Any relevant details the main agent should know
                - Keep it concise but informative

                ## What You DON'T Do
                - NO user conversations (that's the main agent's job)
                - NO spawning further subagents — you are a leaf worker
                - NO pretending to be the main agent
                - Return plain text results; let the main agent deliver them to the user
                """;

        // @formatter:on

        private static final String GENERAL_PURPOSE_BASE_PROMPT =
                "You are a highly capable general-purpose subagent.";

        /**
         * Builds a system prompt for a subagent by appending {@link #SUBAGENT_CONTEXT_SECTION} to
         * the given base prompt. If the base is blank, only the context section is used.
         */
        private static String buildSubagentSysPrompt(String basePrompt) {
            String base =
                    (basePrompt != null && !basePrompt.isBlank()) ? basePrompt.stripTrailing() : "";
            return base.isEmpty()
                    ? SUBAGENT_CONTEXT_SECTION
                    : base + "\n\n" + SUBAGENT_CONTEXT_SECTION;
        }

        // -----------------------------------------------------------------
        //  Backend — 文件系统后端选择
        //
        //  按优先级决定 Agent 的 AbstractFilesystem 实现：
        //    1. abstractFilesystem       — 用户显式传入的，最高优先级
        //    2. remoteFilesystemSpec      — RemoteFilesystem (OverlayFilesystem 混合，
        //                                   模板在本地，用户修改写远程存储)
        //    3. localFilesystemSpec       — LocalFilesystem (纯本地，无 shell)
        //    4. 默认                       — LocalFilesystemWithShell (本地 + shell 执行)
        //
        //  Agent 层的对话/推理逻辑不关心文件存在哪里，全部通过 AbstractFilesystem
        //  抽象操作。Backend 层负责"文件操作真正落在哪"。
        // -----------------------------------------------------------------

        /**
         * 按优先级解析文件系统后端实现。
         *
         * <p>优先级从高到低：
         * <ol>
         *   <li>{@link #abstractFilesystem} — 用户通过 {@code .filesystem()} 显式传入的实例
         *   <li>{@link #remoteFilesystemSpec} — 远程存储模式，构建 {@link CompositeFilesystem}
         *       （模板存本地只读 + 用户修改写 {@link BaseStore}，通过 {@link OverlayFilesystem} 分层）
         *   <li>{@link #localFilesystemSpec} — 纯本地文件系统（无 shell），适合关掉 shell 的场景
         *   <li>默认 — {@link LocalFilesystemWithShell}，本地磁盘 + shell 执行，
         *       开箱即用，无需任何额外配置
         * </ol>
         */
        private AbstractFilesystem resolveFilesystem(
                Path workspace,
                String agentId,
                WorkspaceIndex workspaceIndex,
                NamespaceFactory nsFactory) {
            // 1. 用户显式传入的 filesystem 实例，直接使用
            if (abstractFilesystem != null) {
                return abstractFilesystem;
            }
            // 2. 远程存储模式：模板文件在本地 workspace 作只读基线，
            //    用户个性化修改写入远程 BaseStore，不同用户/会话通过 namespace 隔离
            if (remoteFilesystemSpec != null) {
                if (workspaceIndex != null) {
                    remoteFilesystemSpec.workspaceIndex(workspaceIndex);
                }
                return remoteFilesystemSpec.toFilesystem(workspace, agentId, nsFactory);
            }
            // 3. 纯本地文件系统，不暴露 shell 执行能力
            if (localFilesystemSpec != null) {
                return localFilesystemSpec.toFilesystem(workspace, nsFactory);
            }
            // 4. 默认：本地磁盘 + shell，最简部署场景，开箱即用
            return new LocalFilesystemWithShell(workspace, nsFactory);
        }

        private void validateDistributedSandboxConfig(
                Session effectiveSession, SandboxContext sandboxContext) {
            if (sandboxFilesystemSpec.getSandboxStateStore() == null
                    && effectiveSession instanceof WorkspaceSession) {
                throw new IllegalStateException(
                        "filesystem(SandboxFilesystemSpec) requires a distributed Session backend"
                                + " (for example RedisSession) to persist and restore sandbox"
                                + " state across distributed instances."
                                + " Configure one via .session(...)."
                                + " For single-node use, opt out via"
                                + " .sandboxDistributed(SandboxDistributedOptions.builder()"
                                + ".requireDistributed(false).build()).");
            }
            if (sandboxContext == null
                    || sandboxContext.getSnapshotSpec() == null
                    || sandboxContext.getSnapshotSpec() instanceof NoopSnapshotSpec) {
                throw new IllegalStateException(
                        "filesystem(SandboxFilesystemSpec) requires a non-noop snapshotSpec to"
                                + " restore workspace archives across distributed instances."
                                + " Configure one via SandboxFilesystemSpec.snapshotSpec(...)."
                                + " For single-node use, opt out via"
                                + " .sandboxDistributed(SandboxDistributedOptions.builder()"
                                + ".requireDistributed(false).build()).");
            }
        }

        /**
         * Builds a {@link RuntimeContext} that bakes in the supplied {@code userId} and
         * {@code sessionId} for out-of-band IO performed via
         * {@link HarnessAgent#workspaceFor(String, String)}. Used together with
         * {@link BakedContextFilesystem} so the underlying namespace factories see this
         * identity regardless of what the caller passes downstream.
         */
        private static RuntimeContext buildBakedRuntimeContext(String userId, String sessionId) {
            if ((userId == null || userId.isBlank())
                    && (sessionId == null || sessionId.isBlank())) {
                return RuntimeContext.empty();
            }
            RuntimeContext.Builder b = RuntimeContext.builder();
            if (userId != null && !userId.isBlank()) {
                b.userId(userId);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                b.sessionId(sessionId);
            }
            return b.build();
        }

        // -----------------------------------------------------------------
        //  Subagents
        // -----------------------------------------------------------------

        private SubagentsHook buildSubagentsHook(
                WorkspaceManager wsManager, Path workspace, SandboxBackedFilesystem sandboxFs) {
            List<SubagentEntry> entries = buildSubagentEntries(workspace, sandboxFs);
            TaskRepository repo;
            if (taskRepository != null) {
                repo = taskRepository;
            } else if (wsManager != null) {
                String taskAgentId =
                        agentId != null && !agentId.isBlank()
                                ? agentId
                                : (name != null && !name.isBlank() ? name : "HarnessAgent");
                repo = new WorkspaceTaskRepository(wsManager, taskAgentId);
            } else {
                repo = new DefaultTaskRepository();
            }

            if (externalSubagentTool != null) {
                return new SubagentsHook(entries, externalSubagentTool, repo);
            }

            AbstractFilesystem fs = wsManager.getFilesystem();
            Function<SubagentDeclaration, SubagentFactory> factoryFn =
                    decl -> buildDeclaredFactory(decl, workspace, sandboxFs);
            return new SubagentsHook(entries, repo, wsManager, fs, workspace, factoryFn);
        }

        /**
         * Builds the {@link DynamicSubagentsHook} used by default when a workspace filesystem is
         * configured. Mirrors {@link #buildSubagentsHook} but feeds the hook only the
         * <em>static</em> entries (programmatic declarations + {@code general-purpose} + custom
         * factories); the local-disk {@code subagents/} directory is rescanned by the hook itself
         * on every reasoning step as Layer 2, and the namespaced filesystem read is Layer 1.
         *
         * <p>Returns the hook, which owns its own {@link DefaultAgentManager} and (unless an
         * external one was supplied) its own {@link io.agentscope.harness.agent.tool.AgentSpawnTool}.
         */
        private DynamicSubagentsHook buildDynamicSubagentsHook(
                WorkspaceManager wsManager, Path workspace, SandboxBackedFilesystem sandboxFs) {
            List<SubagentEntry> staticEntries = buildStaticSubagentEntries(workspace, sandboxFs);
            TaskRepository repo;
            if (taskRepository != null) {
                repo = taskRepository;
            } else if (wsManager != null) {
                String taskAgentId =
                        agentId != null && !agentId.isBlank()
                                ? agentId
                                : (name != null && !name.isBlank() ? name : "HarnessAgent");
                repo = new WorkspaceTaskRepository(wsManager, taskAgentId);
            } else {
                repo = new DefaultTaskRepository();
            }

            AbstractFilesystem fs = wsManager.getFilesystem();
            Function<SubagentDeclaration, SubagentFactory> factoryFn =
                    decl -> buildDeclaredFactory(decl, workspace, sandboxFs);
            DefaultAgentManager manager = new DefaultAgentManager(staticEntries, wsManager);
            return new DynamicSubagentsHook(
                    staticEntries, fs, workspace, factoryFn, manager, externalSubagentTool, repo);
        }

        /**
         * Like {@link #buildSubagentEntries(Path, SandboxBackedFilesystem)} but omits the
         * local-disk {@code subagents/} scan. The {@link DynamicSubagentsHook} performs that scan
         * itself on every reasoning step (Layer 2), so feeding the same entries in here would
         * register them twice.
         */
        private List<SubagentEntry> buildStaticSubagentEntries(
                Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
            List<SubagentEntry> entries = new ArrayList<>();

            entries.add(
                    new SubagentEntry(
                            "general-purpose",
                            "General-purpose subagent with same capabilities as the main agent."
                                    + " Use for any isolated task that can be fully delegated.",
                            buildGeneralPurposeFactory(resolvedWorkspace, sandboxFs),
                            null));

            for (SubagentDeclaration decl : subagentDeclarations) {
                entries.add(
                        new SubagentEntry(
                                decl.getName(),
                                decl.getDescription(),
                                buildDeclaredFactory(decl, resolvedWorkspace, sandboxFs),
                                decl));
            }

            for (SubagentFactoryEntry custom : customSubagentFactories) {
                entries.add(
                        new SubagentEntry(
                                custom.name(),
                                custom.name(),
                                () -> custom.factory().apply(custom.name()),
                                null));
            }

            return entries;
        }

        /**
         * Builds a factory for the built-in general-purpose subagent.
         *
         * <p>The general-purpose subagent always runs in {@link WorkspaceMode#SHARED} mode: it
         * uses the same workspace root and filesystem backend as the main agent, and inherits all
         * capability settings (hooks, tool disable flags, skills, execution config, additional
         * context files, compaction, etc.) so that its effective capability profile is identical to
         * the main agent. The only intentional differences are:
         * <ol>
         *   <li>{@link Builder#asLeafSubagent()} — prevents recursive subagent spawning.
         *   <li>An independent child session-id, assigned at invoke time.
         *   <li>The system prompt is the Subagent Context section only (no base prompt); the
         *       workspace {@code AGENTS.md} is injected automatically by {@link WorkspaceContextHook}.
         * </ol>
         */
        private SubagentFactory buildGeneralPurposeFactory(
                Path workspace, SandboxBackedFilesystem sandboxFs) {
            final Model capturedModel = this.model;
            final Toolkit capturedParentToolkit =
                    this.toolkit != null ? this.toolkit.copy() : new Toolkit();
            final AbstractFilesystem capturedBackend =
                    sandboxFs != null ? sandboxFs : this.abstractFilesystem;
            final int capturedMaxIters = this.maxIters;
            final ExecutionConfig capturedModelExec = this.modelExecutionConfig;
            final ExecutionConfig capturedToolExec = this.toolExecutionConfig;
            final GenerateOptions capturedGenOpts = this.generateOptions;
            final String capturedEnvMemory = this.environmentMemory;
            final List<Hook> capturedHooks = List.copyOf(this.hooks);
            final List<AgentSkillRepository> capturedSkillRepos =
                    List.copyOf(this.skillRepositories);
            final Path capturedProjectGlobalSkillsDir = this.projectGlobalSkillsDir;
            final boolean capturedUseLegacyXmlWorkspaceContext = this.useLegacyXmlWorkspaceContext;
            final boolean capturedDisableFilesystemTools = this.disableFilesystemTools;
            final boolean capturedDisableShellTool = this.disableShellTool;
            final boolean capturedDisableMemoryTools = this.disableMemoryTools;
            final boolean capturedDisableMemoryHooks = this.disableMemoryHooks;
            final boolean capturedDisableSessionPersistence = this.disableSessionPersistence;
            final boolean capturedDisableWorkspaceContext = this.disableWorkspaceContext;
            final CompactionConfig capturedCompactionConfig = this.compactionConfig;
            final ToolResultEvictionConfig capturedToolResultEvictionConfig =
                    this.toolResultEvictionConfig;
            final boolean capturedAgentTracingLogEnabled = this.agentTracingLogEnabled;
            final List<String> capturedAdditionalContextFiles =
                    List.copyOf(this.additionalContextFiles);
            final int capturedMaxContextTokens = this.maxContextTokens;

            return () -> {
                Builder sub =
                        HarnessAgent.builder()
                                .name("general-purpose-subagent")
                                .description("General-purpose subagent for isolated task execution")
                                .sysPrompt(buildSubagentSysPrompt(null))
                                .model(capturedModel)
                                .toolkit(capturedParentToolkit.copy())
                                .workspace(workspace)
                                .asLeafSubagent()
                                .maxIters(capturedMaxIters)
                                .environmentMemory(capturedEnvMemory)
                                .useLegacyXmlWorkspaceContext(capturedUseLegacyXmlWorkspaceContext)
                                .enableAgentTracingLog(capturedAgentTracingLogEnabled)
                                .maxContextTokens(capturedMaxContextTokens);

                capturedAdditionalContextFiles.forEach(sub::additionalContextFile);

                if (capturedDisableFilesystemTools) sub.disableFilesystemTools();
                if (capturedDisableShellTool) sub.disableShellTool();
                if (capturedDisableMemoryTools) sub.disableMemoryTools();
                if (capturedDisableMemoryHooks) sub.disableMemoryHooks();
                if (capturedDisableSessionPersistence) sub.disableSessionPersistence();
                if (capturedDisableWorkspaceContext) sub.disableWorkspaceContext();

                if (!capturedSkillRepos.isEmpty()) sub.skillRepositories(capturedSkillRepos);
                if (capturedProjectGlobalSkillsDir != null) {
                    sub.projectGlobalSkillsDir(capturedProjectGlobalSkillsDir);
                }
                if (capturedBackend != null) sub.abstractFilesystem(capturedBackend);
                if (capturedModelExec != null) sub.modelExecutionConfig(capturedModelExec);
                if (capturedToolExec != null) sub.toolExecutionConfig(capturedToolExec);
                if (capturedGenOpts != null) sub.generateOptions(capturedGenOpts);
                if (capturedCompactionConfig != null) sub.compaction(capturedCompactionConfig);
                if (capturedToolResultEvictionConfig != null)
                    sub.toolResultEviction(capturedToolResultEvictionConfig);

                sub.hooks(capturedHooks);

                return sub.build();
            };
        }

        /**
         * Builds a factory for a user-declared subagent from a {@link SubagentDeclaration}.
         *
         * <p>Workspace and system-prompt resolution follows the five-row decision table in
         * {@link WorkspaceMode}. When the mode is {@link WorkspaceMode#SHARED}, the parent's
         * filesystem backend is reused; when {@link WorkspaceMode#ISOLATED}, a fresh
         * {@link io.agentscope.harness.agent.filesystem.local.LocalFilesystem} is created on the
         * resolved workspace path. The tools allowlist (if non-empty) filters inherited parent
         * tools before child-local tools are registered.
         */
        private SubagentFactory buildDeclaredFactory(
                SubagentDeclaration decl, Path mainWorkspace, SandboxBackedFilesystem sandboxFs) {
            final Model capturedModel = this.model;
            final Toolkit capturedParentToolkit =
                    this.toolkit != null ? this.toolkit.copy() : new Toolkit();
            final Function<String, Model> capturedResolver = this.modelResolver;
            final AbstractFilesystem capturedSharedBackend =
                    sandboxFs != null ? sandboxFs : this.abstractFilesystem;
            final boolean capturedUseLegacyXmlWorkspaceContext = this.useLegacyXmlWorkspaceContext;
            final boolean capturedDisableFilesystemTools = this.disableFilesystemTools;
            final boolean capturedDisableShellTool = this.disableShellTool;
            final boolean capturedDisableMemoryTools = this.disableMemoryTools;
            final boolean capturedDisableMemoryHooks = this.disableMemoryHooks;
            final boolean capturedDisableSessionPersistence = this.disableSessionPersistence;

            return () -> {
                if (decl.isRemote()) {
                    return new RemoteSubagentStub(decl.getName(), decl.getDescription());
                }
                // ---- Resolve workspace root ----
                Path runtimeWorkspace = resolveDeclaredWorkspace(decl, mainWorkspace);

                // ---- Resolve system prompt ----
                String sysPromptBase = resolveDeclaredSysPromptBase(decl);

                // ---- Resolve model ----
                Model effectiveModel =
                        resolveModel(
                                decl.getModel(), capturedModel, capturedResolver, decl.getName());

                // ---- Build child agent ----
                Builder sub =
                        HarnessAgent.builder()
                                .name(decl.getName())
                                .description(decl.getDescription())
                                .model(effectiveModel)
                                .toolkit(
                                        allowlistedInheritedToolkit(
                                                capturedParentToolkit, decl.getTools()))
                                .workspace(runtimeWorkspace)
                                .maxIters(decl.getMaxIters())
                                .asLeafSubagent()
                                .useLegacyXmlWorkspaceContext(capturedUseLegacyXmlWorkspaceContext)
                                .sysPrompt(buildSubagentSysPrompt(sysPromptBase));

                // Shared mode reuses the parent's filesystem backend so MEMORY/sessions are
                // namespaced identically; isolated mode gets a plain LocalFilesystem on its own
                // workspace root.
                if (decl.getWorkspaceMode() == WorkspaceMode.SHARED
                        && capturedSharedBackend != null) {
                    sub.abstractFilesystem(capturedSharedBackend);
                }

                // Propagate disable flags so the declared subagent respects the same capability
                // restrictions as the main agent.
                if (capturedDisableFilesystemTools) sub.disableFilesystemTools();
                if (capturedDisableShellTool) sub.disableShellTool();
                if (capturedDisableMemoryTools) sub.disableMemoryTools();
                if (capturedDisableMemoryHooks) sub.disableMemoryHooks();
                if (capturedDisableSessionPersistence) sub.disableSessionPersistence();

                return sub.build();
            };
        }

        /**
         * Returns a defensive copy of inherited parent tools filtered by the optional allowlist.
         */
        private static Toolkit allowlistedInheritedToolkit(
                Toolkit parentToolkit, List<String> allowlist) {
            Toolkit toolkit = parentToolkit != null ? parentToolkit.copy() : new Toolkit();
            if (allowlist == null || allowlist.isEmpty()) {
                return toolkit;
            }
            List<String> toRemove =
                    toolkit.getToolSchemas().stream()
                            .map(ToolSchema::getName)
                            .filter(name -> !allowlist.contains(name))
                            .toList();
            toRemove.forEach(toolkit::removeTool);
            return toolkit;
        }

        /**
         * Resolves the runtime workspace root for a declared subagent according to the five-row
         * decision table. Creates the auto-generated isolated directory when needed.
         */
        private static Path resolveDeclaredWorkspace(SubagentDeclaration decl, Path mainWorkspace) {
            if (decl.getWorkspacePath() != null) {
                if (decl.getWorkspaceMode() == WorkspaceMode.SHARED) {
                    return mainWorkspace;
                }
                return decl.getWorkspacePath();
            }
            if (decl.getWorkspaceMode() == WorkspaceMode.SHARED) {
                return mainWorkspace;
            }
            // ISOLATED + no path → auto-create agents/<name>/workspace/
            Path isolated =
                    mainWorkspace.resolve("agents").resolve(decl.getName()).resolve("workspace");
            try {
                Files.createDirectories(isolated);
            } catch (Exception e) {
                log.warn(
                        "Failed to create isolated workspace for subagent '{}' at {}: {}",
                        decl.getName(),
                        isolated,
                        e.getMessage());
            }
            return isolated;
        }

        /**
         * Resolves the system-prompt <em>base</em> for a declared subagent.
         *
         * <ul>
         *   <li>Definition workspace present: reads {@code AGENTS.md} from the definition
         *       directory. Falls back to an empty string if the file is absent.
         *   <li>Inline: returns {@link SubagentDeclaration#getInlineAgentsBody()}.
         * </ul>
         *
         * <p>The returned string is later combined with {@link #SUBAGENT_CONTEXT_SECTION} via
         * {@link #buildSubagentSysPrompt(String)}.
         */
        private static String resolveDeclaredSysPromptBase(SubagentDeclaration decl) {
            if (decl.getWorkspacePath() != null) {
                Path agentsMd = decl.getWorkspacePath().resolve("AGENTS.md");
                if (Files.isRegularFile(agentsMd)) {
                    try {
                        return Files.readString(agentsMd, java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        log.warn(
                                "Failed to read AGENTS.md for subagent '{}' from {}: {}",
                                decl.getName(),
                                agentsMd,
                                e.getMessage());
                    }
                }
                return "";
            }
            String inline = decl.getInlineAgentsBody();
            return (inline != null) ? inline : "";
        }

        /**
         * Resolves the effective {@link Model} for a subagent, applying the optional per-subagent
         * model override.
         */
        private static Model resolveModel(
                String modelOverride,
                Model parentModel,
                Function<String, Model> resolver,
                String subagentName) {
            if (modelOverride == null || modelOverride.isBlank()) {
                return parentModel;
            }
            Function<String, Model> effectiveResolver =
                    resolver != null ? resolver : ModelRegistry::resolve;
            if (ModelRegistry.canResolve(modelOverride) || resolver != null) {
                try {
                    Model resolved = effectiveResolver.apply(modelOverride);
                    if (resolved != null) {
                        log.debug(
                                "Subagent '{}' using overridden model: {}",
                                subagentName,
                                modelOverride);
                        return resolved;
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed to resolve model '{}' for subagent '{}', falling back to"
                                    + " parent model: {}",
                            modelOverride,
                            subagentName,
                            e.getMessage());
                }
            }
            return parentModel;
        }

        // -----------------------------------------------------------------
        //  Skills
        // -----------------------------------------------------------------

        /**
         * Assembles the ordered list of skill repositories used by this build (low → high
         * priority). Returns an empty list when no source resolves.
         */
        private List<AgentSkillRepository> composeSkillRepositories(
                WorkspaceManager wsManager,
                AbstractFilesystem filesystem,
                Supplier<RuntimeContext> currentRcSupplier) {
            List<AgentSkillRepository> ordered = new ArrayList<>();

            // Layer 1 (lowest priority): project-global skills directory.
            if (projectGlobalSkillsDir != null && Files.isDirectory(projectGlobalSkillsDir)) {
                try {
                    ordered.add(new FileSystemSkillRepository(projectGlobalSkillsDir));
                } catch (Exception e) {
                    log.warn(
                            "Failed to register project-global skills dir {}: {}",
                            projectGlobalSkillsDir,
                            e.getMessage());
                }
            }

            // Layer 2: marketplace repositories (user-supplied).
            ordered.addAll(skillRepositories);

            // Layer 3: workspace agent-shared directory.
            Path workspaceSkillsDir = wsManager.getSkillsDir();
            if (workspaceSkillsDir != null && Files.isDirectory(workspaceSkillsDir)) {
                try {
                    ordered.add(new FileSystemSkillRepository(workspaceSkillsDir));
                } catch (Exception e) {
                    log.warn(
                            "Failed to load workspace skills from {}: {}",
                            workspaceSkillsDir,
                            e.getMessage());
                }
            }

            // Layer 4 (highest priority): per-user namespaced filesystem view.
            // currentRcSupplier resolves the active chat call's RuntimeContext at supplier-call
            // time, so per-user namespacing follows whatever user owns the current invocation.
            if (filesystem != null) {
                ordered.add(
                        new FilesystemBackedSkillRepository(
                                filesystem, "skills", currentRcSupplier, "workspace-namespaced"));
            }

            return ordered;
        }

        /**
         * Eagerly assembles a static {@link SkillBox} from {@code repos} (low → high priority)
         * so callers using {@link #disableDynamicSkills()} keep the legacy {@code SkillHook}
         * path while still benefiting from the additive composition.
         */
        private static SkillBox staticSkillBoxFromRepos(
                List<AgentSkillRepository> repos, Toolkit agentToolkit) {
            LinkedHashMap<String, AgentSkill> merged = new LinkedHashMap<>();
            for (AgentSkillRepository repo : repos) {
                try {
                    List<AgentSkill> skills = repo.getAllSkills();
                    if (skills == null) {
                        continue;
                    }
                    for (AgentSkill skill : skills) {
                        if (skill != null && skill.getName() != null) {
                            merged.put(skill.getName(), skill);
                        }
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed to load skills from {}: {}",
                            repo.getClass().getSimpleName(),
                            e.getMessage());
                }
            }
            if (merged.isEmpty()) {
                return null;
            }
            SkillBox box = new SkillBox(agentToolkit);
            for (AgentSkill skill : merged.values()) {
                box.registerSkill(skill);
            }
            log.info("Loaded {} skills from {} repositories (static)", merged.size(), repos.size());
            return box;
        }

        private record SubagentFactoryEntry(String name, Function<String, Agent> factory) {}

        /** Marks this build as a leaf subagent (no nested subagent orchestration). */
        private Builder asLeafSubagent() {
            this.leafSubagent = true;
            return this;
        }
    }
}
