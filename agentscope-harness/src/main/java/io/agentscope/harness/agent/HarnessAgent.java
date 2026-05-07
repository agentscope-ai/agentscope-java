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
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.RemoteFilesystemSpec;
import io.agentscope.harness.agent.hook.AgentTraceHook;
import io.agentscope.harness.agent.hook.CompactionHook;
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
import io.agentscope.harness.agent.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxStateStore;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.filesystem.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.session.WorkspaceSession;
import io.agentscope.harness.agent.store.NamespaceFactory;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.SubagentSpec;
import io.agentscope.harness.agent.subagent.task.DefaultTaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.MemoryGetTool;
import io.agentscope.harness.agent.tool.MemorySearchTool;
import io.agentscope.harness.agent.tool.SessionSearchTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
public class HarnessAgent implements Agent, StateModule {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgent.class);

    private final ReActAgent delegate;
    private final WorkspaceManager workspaceManager;
    private final CompactionHook compactionHook;
    private final AtomicReference<String> userIdRef;
    private final AtomicReference<String> sessionIdRef;
    private final Session defaultSession;
    private final SandboxContext defaultSandboxContext;
    private RuntimeContext runtimeContext;

    private HarnessAgent(
            ReActAgent delegate,
            WorkspaceManager workspaceManager,
            CompactionHook compactionHook,
            AtomicReference<String> userIdRef,
            AtomicReference<String> sessionIdRef,
            Session defaultSession,
            SandboxContext defaultSandboxContext) {
        this.delegate = delegate;
        this.workspaceManager = workspaceManager;
        this.compactionHook = compactionHook;
        this.userIdRef = userIdRef;
        this.sessionIdRef = sessionIdRef;
        this.defaultSession = defaultSession;
        this.defaultSandboxContext = defaultSandboxContext;
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
                .compactIfNeeded(allMsgs, forceConfig, agentId, sessionId)
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
        if (userIdRef != null) {
            userIdRef.set(effective.getUserId());
        }
        if (sessionIdRef != null) {
            String sid =
                    effective.getSessionKey() != null
                            ? effective.getSessionKey().toIdentifier()
                            : effective.getSessionId();
            sessionIdRef.set(sid);
        }
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
        Session session = ctx.getSession() != null ? ctx.getSession() : defaultSession;
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

    public RuntimeContext getRuntimeContext() {
        return runtimeContext;
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

    public static class Builder {

        // Core ReActAgent params
        private String name;
        private String description;
        private String sysPrompt;
        private Model model;
        private Toolkit toolkit = new Toolkit();
        private int maxIters = 15;
        private ExecutionConfig modelExecutionConfig;
        private ExecutionConfig toolExecutionConfig;
        private GenerateOptions generateOptions;
        private final List<Hook> hooks = new ArrayList<>();

        /** When {@code null}, skills load from {@code workspace/skills/} via {@link FileSystemSkillRepository}. */
        private AgentSkillRepository skillRepository;

        private ToolExecutionContext toolExecutionContext;

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

        private final List<SubagentSpec> subagentSpecs = new ArrayList<>();
        private final List<SubagentFactoryEntry> customSubagentFactories = new ArrayList<>();
        private TaskRepository taskRepository;
        private Object externalSubagentTool;
        private Function<String, Model> modelResolver;
        private final List<String> additionalContextFiles = new ArrayList<>();
        private int maxContextTokens = 8000;
        private boolean useLegacyXmlWorkspaceContext = false;

        // Filesystem mode configuration (at most one of these three is set)
        private SandboxFilesystemSpec sandboxFilesystemSpec;
        private RemoteFilesystemSpec remoteFilesystemSpec;
        private LocalFilesystemSpec localFilesystemSpec;

        public Builder name(String name) {
            this.name = name;
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
         * Supplies skills from a custom repository (e.g. {@code GitSkillRepository}). A {@link SkillBox} is
         * assembled automatically from this repository and the agent toolkit. When {@code null} (default),
         * skills are loaded from {@code &lt;workspace&gt;/skills/} using {@link FileSystemSkillRepository} when
         * that directory exists.
         */
        public Builder skillRepository(AgentSkillRepository skillRepository) {
            this.skillRepository = skillRepository;
            return this;
        }

        public Builder toolExecutionContext(ToolExecutionContext ctx) {
            this.toolExecutionContext = ctx;
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

        /** Adds a subagent spec (programmatic; workspace specs come from {@code subagents/*.md}). */
        public Builder subagent(SubagentSpec spec) {
            this.subagentSpecs.add(spec);
            return this;
        }

        public Builder subagents(List<SubagentSpec> specs) {
            this.subagentSpecs.addAll(specs);
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
         * Builds the subagent entries from programmatic specs, {@code workspace/subagents/*.md},
         * and custom factories. Useful for callers (e.g. {@code AgentBootstrap}) that need to
         * extract agent factories before building the full agent.
         */
        public List<SubagentEntry> buildSubagentEntries(
                Path resolvedWorkspace, SandboxBackedFilesystem sandboxFs) {
            List<SubagentSpec> allSpecs = new ArrayList<>(subagentSpecs);

            Path subagentsDir = resolvedWorkspace.resolve("subagents");
            if (Files.isDirectory(subagentsDir)) {
                allSpecs.addAll(AgentSpecLoader.loadFromDirectory(subagentsDir));
            }

            List<SubagentEntry> entries = new ArrayList<>();

            entries.add(
                    new SubagentEntry(
                            "general-purpose",
                            "General-purpose subagent with same capabilities as the main agent."
                                    + " Use for any isolated task that can be fully delegated.",
                            buildGeneralPurposeFactory(resolvedWorkspace, sandboxFs)));

            for (SubagentSpec spec : allSpecs) {
                if (spec.getName() != null) {
                    entries.add(
                            new SubagentEntry(
                                    spec.getName(),
                                    spec.getDescription() != null
                                            ? spec.getDescription()
                                            : spec.getName(),
                                    buildSpecFactory(spec, resolvedWorkspace)));
                }
            }

            for (SubagentFactoryEntry custom : customSubagentFactories) {
                entries.add(
                        new SubagentEntry(
                                custom.name(),
                                custom.name(),
                                () -> custom.factory().apply(custom.name())));
            }

            return entries;
        }

        public HarnessAgent build() {
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
            String resolvedAgentId = name != null ? name : "HarnessAgent";
            Session effectiveSession =
                    sandboxDistributedOptions != null
                                    && sandboxDistributedOptions.getSession() != null
                            ? sandboxDistributedOptions.getSession()
                            : session;
            if (effectiveSession == null) {
                effectiveSession = new WorkspaceSession(resolvedWorkspace, resolvedAgentId);
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

            AtomicReference<String> userIdRef = new AtomicReference<>();
            AtomicReference<String> sessionIdRef = new AtomicReference<>();
            AbstractFilesystem filesystem =
                    resolveFilesystem(resolvedWorkspace, resolvedAgentId, userIdRef, sessionIdRef);

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
                capturedSandboxFs.configureNamespace(buildDynamicNamespaceFactory(userIdRef));
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

                SandboxStateStore stateStore =
                        sandboxFilesystemSpec.getSandboxStateStore() != null
                                ? sandboxFilesystemSpec.getSandboxStateStore()
                                : new SessionSandboxStateStore(effectiveSession, resolvedAgentId);
                SandboxManager sandboxManager =
                        new SandboxManager(
                                defaultSandboxContext.getClient(), stateStore, resolvedAgentId);
                sandboxLifecycleHook = new SandboxLifecycleHook(sandboxManager, capturedSandboxFs);
            }
            WorkspaceManager wsManager = new WorkspaceManager(resolvedWorkspace, filesystem);
            wsManager.validate();

            Memory memory = new InMemoryMemory();

            // ---- Hooks ----
            List<Hook> allHooks = new ArrayList<>(hooks);

            // Sandbox lifecycle hook runs first (priority=50) — acquire/release sandbox session
            if (sandboxLifecycleHook != null) {
                allHooks.add(sandboxLifecycleHook);
            }

            if (agentTracingLogEnabled) {
                allHooks.add(new AgentTraceHook());
            }

            WorkspaceContextHook markdownHook =
                    new WorkspaceContextHook(
                            wsManager,
                            name != null ? name : "HarnessAgent",
                            environmentMemory,
                            maxContextTokens);
            markdownHook.setAdditionalContextFiles(additionalContextFiles);
            allHooks.add(markdownHook);

            MemoryFlushHook memoryFlushHook = null;
            if (model != null) {
                memoryFlushHook = new MemoryFlushHook(wsManager, model);
                allHooks.add(memoryFlushHook);
            }

            if (model != null) {
                MemoryConsolidator consolidator = new MemoryConsolidator(wsManager, model);
                allHooks.add(new MemoryMaintenanceHook(wsManager, consolidator));
            }

            CompactionHook compactionHook = null;
            if (compactionConfig != null && model != null) {
                compactionHook = new CompactionHook(wsManager, model, compactionConfig);
                allHooks.add(compactionHook);
            }

            if (toolResultEvictionConfig != null) {
                allHooks.add(new ToolResultEvictionHook(filesystem, toolResultEvictionConfig));
            }

            SessionPersistenceHook sessionPersistenceHook = new SessionPersistenceHook();
            allHooks.add(sessionPersistenceHook);

            if (!leafSubagent && model != null) {
                SubagentsHook subagentsHook =
                        buildSubagentsHook(wsManager, resolvedWorkspace, capturedSandboxFs);
                if (subagentsHook != null) {
                    allHooks.add(subagentsHook);
                }
            }

            // ---- Toolkit ----
            Toolkit agentToolkit = toolkit;

            MemorySearchTool searchTool = new MemorySearchTool(wsManager);
            MemoryGetTool getTool = new MemoryGetTool(wsManager);

            agentToolkit.registerTool(searchTool);
            agentToolkit.registerTool(getTool);
            agentToolkit.registerTool(new SessionSearchTool(wsManager));

            agentToolkit.registerTool(new FilesystemTool(filesystem));

            if (filesystem instanceof AbstractSandboxFilesystem sandbox) {
                agentToolkit.registerTool(new ShellExecuteTool(sandbox));
            }

            // ---- Skills (SkillBox assembled from optional AgentSkillRepository or default FS
            // repo) ----
            SkillBox effectiveSkillBox = resolveSkillBox(wsManager, agentToolkit);

            // ---- Build ReActAgent ----
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

            ReActAgent delegate = reactBuilder.build();

            log.info(
                    "HarnessAgent '{}' built [workspace={}, backend={}, subagents={}]",
                    name,
                    resolvedWorkspace,
                    filesystem.getClass().getSimpleName(),
                    !leafSubagent && model != null);

            return new HarnessAgent(
                    delegate,
                    wsManager,
                    compactionHook,
                    userIdRef,
                    sessionIdRef,
                    effectiveSession,
                    defaultSandboxContext);
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
        //  Backend
        // -----------------------------------------------------------------

        private AbstractFilesystem resolveFilesystem(
                Path workspace,
                String agentId,
                AtomicReference<String> userIdRef,
                AtomicReference<String> sessionIdRef) {
            if (abstractFilesystem != null) {
                return abstractFilesystem;
            }
            NamespaceFactory nsFactory = buildDynamicNamespaceFactory(userIdRef);
            if (remoteFilesystemSpec != null) {
                return remoteFilesystemSpec.toFilesystem(
                        workspace, agentId, nsFactory, userIdRef::get, sessionIdRef::get);
            }
            if (localFilesystemSpec != null) {
                return localFilesystemSpec.toFilesystem(workspace, nsFactory);
            }
            // Default to Mode 3 with out-of-the-box LocalFilesystemWithShell settings.
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

        private static NamespaceFactory buildDynamicNamespaceFactory(
                AtomicReference<String> userIdRef) {
            return () -> {
                String userId = userIdRef.get();
                if (userId == null || userId.isBlank()) {
                    return List.of();
                }
                return List.of(userId);
            };
        }

        // -----------------------------------------------------------------
        //  Subagents
        // -----------------------------------------------------------------

        private SubagentsHook buildSubagentsHook(
                WorkspaceManager wsManager, Path workspace, SandboxBackedFilesystem sandboxFs) {
            List<SubagentEntry> entries = buildSubagentEntries(workspace, sandboxFs);
            TaskRepository repo =
                    taskRepository != null ? taskRepository : new DefaultTaskRepository();

            if (externalSubagentTool != null) {
                return new SubagentsHook(entries, externalSubagentTool, repo);
            }
            return new SubagentsHook(entries, repo, wsManager);
        }

        /**
         * Builds a factory for the general-purpose subagent. It creates a new HarnessAgent that
         * mirrors the main agent's configuration (same model, workspace, file system, user hooks)
         * but disables subagent support to prevent recursive spawning.
         */
        private SubagentFactory buildGeneralPurposeFactory(
                Path workspace, SandboxBackedFilesystem sandboxFs) {
            // Capture builder state for the closure
            final Model capturedModel = this.model;
            final AbstractFilesystem capturedBackend =
                    sandboxFs != null ? sandboxFs : this.abstractFilesystem;
            final int capturedMaxIters = this.maxIters;
            final ExecutionConfig capturedModelExec = this.modelExecutionConfig;
            final ExecutionConfig capturedToolExec = this.toolExecutionConfig;
            final GenerateOptions capturedGenOpts = this.generateOptions;
            final String capturedEnvMemory = this.environmentMemory;
            final List<Hook> capturedHooks = List.copyOf(this.hooks);
            final AgentSkillRepository capturedSkillRepo = this.skillRepository;
            final boolean capturedUseLegacyXmlWorkspaceContext = this.useLegacyXmlWorkspaceContext;

            return () -> {
                Builder sub =
                        HarnessAgent.builder()
                                .name("general-purpose-subagent")
                                .description("General-purpose subagent for isolated task execution")
                                .sysPrompt(buildSubagentSysPrompt(GENERAL_PURPOSE_BASE_PROMPT))
                                .model(capturedModel)
                                .workspace(workspace)
                                .asLeafSubagent()
                                .maxIters(capturedMaxIters)
                                .environmentMemory(capturedEnvMemory)
                                .useLegacyXmlWorkspaceContext(capturedUseLegacyXmlWorkspaceContext);

                if (capturedSkillRepo != null) {
                    sub.skillRepository(capturedSkillRepo);
                }
                if (capturedBackend != null) {
                    sub.abstractFilesystem(capturedBackend);
                }
                if (capturedModelExec != null) {
                    sub.modelExecutionConfig(capturedModelExec);
                }
                if (capturedToolExec != null) {
                    sub.toolExecutionConfig(capturedToolExec);
                }
                if (capturedGenOpts != null) {
                    sub.generateOptions(capturedGenOpts);
                }
                sub.hooks(capturedHooks);

                return sub.build();
            };
        }

        /**
         * Builds a factory for a spec-based subagent. The resulting HarnessAgent is fully
         * independent from the main agent — it uses the spec's own system prompt, workspace,
         * and configuration. Supports per-subagent {@code model} override via an explicit {@code
         * modelResolver}, or by default {@link ModelRegistry#resolve(String)}.
         */
        private SubagentFactory buildSpecFactory(SubagentSpec spec, Path defaultWorkspace) {
            final Model capturedModel = this.model;
            final Function<String, Model> capturedResolver = this.modelResolver;
            final AgentSkillRepository capturedSkillRepo = this.skillRepository;
            final boolean capturedUseLegacyXmlWorkspaceContext = this.useLegacyXmlWorkspaceContext;

            return () -> {
                Path specWorkspace =
                        (spec.getWorkspace() != null && !spec.getWorkspace().isBlank())
                                ? Path.of(spec.getWorkspace())
                                : defaultWorkspace;

                Function<String, Model> effectiveResolver =
                        capturedResolver != null ? capturedResolver : ModelRegistry::resolve;

                Model effectiveModel = capturedModel;
                if (spec.getModel() != null && !spec.getModel().isBlank()) {
                    String specModel = spec.getModel().trim();
                    if (ModelRegistry.canResolve(specModel) || capturedResolver != null) {
                        try {
                            Model resolved = effectiveResolver.apply(specModel);
                            if (resolved != null) {
                                effectiveModel = resolved;
                                log.debug(
                                        "Subagent '{}' using overridden model: {}",
                                        spec.getName(),
                                        spec.getModel());
                            }
                        } catch (Exception e) {
                            log.warn(
                                    "Failed to resolve model '{}' for subagent '{}', falling back"
                                            + " to parent model: {}",
                                    spec.getModel(),
                                    spec.getName(),
                                    e.getMessage());
                        }
                    }
                }

                Builder sub =
                        HarnessAgent.builder()
                                .name(spec.getName())
                                .description(
                                        spec.getDescription() != null ? spec.getDescription() : "")
                                .model(effectiveModel)
                                .workspace(specWorkspace)
                                .maxIters(spec.getMaxIters())
                                .asLeafSubagent()
                                .useLegacyXmlWorkspaceContext(capturedUseLegacyXmlWorkspaceContext);

                if (capturedSkillRepo != null) {
                    sub.skillRepository(capturedSkillRepo);
                }
                sub.sysPrompt(buildSubagentSysPrompt(spec.getSysPrompt()));

                return sub.build();
            };
        }

        // -----------------------------------------------------------------
        //  Skills
        // -----------------------------------------------------------------

        private SkillBox resolveSkillBox(WorkspaceManager wsManager, Toolkit agentToolkit) {
            if (skillRepository != null) {
                return skillBoxFromRepository(skillRepository, agentToolkit);
            }
            Path skillsDir = wsManager.getSkillsDir();
            if (!Files.isDirectory(skillsDir)) {
                return null;
            }
            try {
                return skillBoxFromRepository(
                        new FileSystemSkillRepository(skillsDir), agentToolkit);
            } catch (Exception e) {
                log.warn("Failed to auto-load skills from {}: {}", skillsDir, e.getMessage());
                return null;
            }
        }

        private static SkillBox skillBoxFromRepository(
                AgentSkillRepository repo, Toolkit agentToolkit) {
            try {
                List<AgentSkill> skills = repo.getAllSkills();
                if (skills == null || skills.isEmpty()) {
                    return null;
                }
                SkillBox box = new SkillBox(agentToolkit);
                for (AgentSkill skill : skills) {
                    box.registerSkill(skill);
                }
                log.info(
                        "Loaded {} skills from {}",
                        skills.size(),
                        repo.getRepositoryInfo() != null
                                ? repo.getRepositoryInfo()
                                : repo.getClass().getSimpleName());
                return box;
            } catch (Exception e) {
                log.warn("Failed to load skills from repository: {}", e.getMessage());
                return null;
            }
        }

        private record SubagentFactoryEntry(String name, Function<String, Agent> factory) {}

        /** Marks this build as a leaf subagent (no nested subagent orchestration). */
        private Builder asLeafSubagent() {
            this.leafSubagent = true;
            return this;
        }
    }
}
