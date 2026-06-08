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
package io.agentscope.harness.coding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.DockerFilesystemSpec;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.DefaultTaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.coding.agent.CodingAgentFactory;
import io.agentscope.harness.coding.agent.ReviewerAgentFactory;
import io.agentscope.harness.coding.config.AgentConfigEntry;
import io.agentscope.harness.coding.config.AgentscopeConfig;
import io.agentscope.harness.coding.config.ChannelConfigEntry;
import io.agentscope.harness.coding.config.SkillRepositorySupport;
import io.agentscope.harness.coding.gateway.HarnessGateway;
import io.agentscope.harness.coding.middleware.MessageQueueMiddleware;
import io.agentscope.harness.coding.middleware.ModelCallLimitMiddleware;
import io.agentscope.harness.coding.middleware.ThreadBudgetMiddleware;
import io.agentscope.harness.coding.session.AgentManagerConfig;
import io.agentscope.harness.coding.session.SessionAgentManager;
import io.agentscope.harness.coding.session.SessionStore;
import io.agentscope.harness.coding.session.SubagentRunRegistry;
import io.agentscope.harness.coding.session.tool.SessionsTool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single API class for assembling, configuring, and running the agentscope harness.
 *
 * <h2>Build phase — {@link #builder()}</h2>
 *
 * Loads {@code ~/.agentscope/codingagent/agentscope.json} (the per-app home, isolated from other
 * harness apps and from the cwd the JVM was launched in), merges file-based agent definitions with
 * programmatic {@link Builder} configuration, and produces {@link HarnessAgent} instances wired
 * with {@link SessionsTool} and a shared {@link SessionAgentManager} + {@link HarnessGateway}.
 *
 * <h2>Runtime phase — two entry points</h2>
 *
 * <ol>
 *   <li>{@link #chatUiChannel()} — obtain a {@link ChatUiChannel} for direct programmatic
 *       interaction (embedded UIs, CLIs, tests). Ready immediately; no {@link #start()} required.
 *   <li>{@link #start()} — initialize and start all pre-registered channel adapters.
 * </ol>
 */
public final class CodingBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CodingBootstrap.class);
    private static final String DEFAULT_MAIN_ID = "default";

    /**
     * Default workspace root for every coding-agent instance. Lives outside the project tree so
     * the agent's skills/memory/sessions don't pollute the cwd it was launched from, and so two
     * different harness apps (codingagent, builder, dataagent, claw) cannot collide on the same
     * {@code .agentscope/workspace/} directory.
     */
    public static final Path DEFAULT_WORKSPACE_ROOT =
            Paths.get(System.getProperty("user.home"), ".agentscope", "codingagent", "workspace");

    /**
     * Default location of the {@code agentscope.json} config file. Pinned to the per-app home
     * directory ({@code ~/.agentscope/codingagent/}) so the coding agent never picks up a stale
     * config left behind by another harness app (e.g. dataagent) in the cwd it was launched from.
     */
    public static final Path DEFAULT_CONFIG_PATH =
            Paths.get(
                    System.getProperty("user.home"),
                    ".agentscope",
                    "codingagent",
                    "agentscope.json");

    // -----------------------------------------------------------------
    //  Instance state — populated by Builder.build()
    // -----------------------------------------------------------------

    private final Path cwd;
    private final Path configPath;
    private final String mainAgentId;
    private final Map<String, HarnessAgent> agents;
    private final AbstractFilesystem globalFilesystem;
    private final List<Channel> registeredChannels;
    private final HarnessGateway gateway;
    private final ChannelManager channelManager;

    private CodingBootstrap(
            Path cwd,
            Path configPath,
            String mainAgentId,
            Map<String, HarnessAgent> agents,
            AbstractFilesystem globalFilesystem,
            List<Channel> registeredChannels,
            HarnessGateway gateway,
            ChannelManager channelManager) {
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.mainAgentId = Objects.requireNonNull(mainAgentId, "mainAgentId");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.globalFilesystem = globalFilesystem;
        this.registeredChannels =
                registeredChannels != null ? List.copyOf(registeredChannels) : List.of();
        this.gateway = gateway;
        this.channelManager = channelManager;
    }

    // -----------------------------------------------------------------
    //  Static factory / utilities
    // -----------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static AgentscopeConfig loadConfig() throws IOException {
        return loadConfigFile(DEFAULT_CONFIG_PATH);
    }

    public static AgentscopeConfig loadConfigFile(Path configPath) throws IOException {
        if (!Files.isRegularFile(configPath)) {
            return new AgentscopeConfig();
        }
        ObjectMapper mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(configPath.toFile(), AgentscopeConfig.class);
    }

    // -----------------------------------------------------------------
    //  Public runtime API
    // -----------------------------------------------------------------

    public ChatUiChannel chatUiChannel() {
        return ChatUiChannel.create(resolveGateway());
    }

    public ChatUiChannel chatUiChannel(ChannelConfig config) {
        return ChatUiChannel.create(resolveGateway(), Objects.requireNonNull(config, "config"));
    }

    public CodingBootstrap start() {
        start(registeredChannels.toArray(new Channel[0]));
        return this;
    }

    public void start(Channel... channels) {
        Objects.requireNonNull(channels, "channels");
        Gateway g = resolveGateway();
        if (channelManager != null) {
            for (Channel channel : channels) {
                if (channel != null) {
                    channelManager.register(channel);
                }
            }
            channelManager.initAll(g);
            channelManager.startAll();
        } else {
            for (Channel channel : channels) {
                if (channel != null) {
                    channel.init(g);
                    channel.start();
                }
            }
        }
    }

    /** Stops all channels managed by the channel manager. */
    public void stop() {
        if (channelManager != null) {
            channelManager.stopAll();
        }
    }

    // -----------------------------------------------------------------
    //  Package-private accessors
    // -----------------------------------------------------------------

    String mainAgentId() {
        return mainAgentId;
    }

    Map<String, HarnessAgent> agents() {
        return agents;
    }

    HarnessAgent mainAgent() {
        HarnessAgent a = agents.get(mainAgentId);
        if (a == null) {
            throw new IllegalStateException("Main agent not registered: " + mainAgentId);
        }
        return a;
    }

    AbstractFilesystem globalFilesystem() {
        return globalFilesystem;
    }

    Path cwd() {
        return cwd;
    }

    Path configPath() {
        return configPath;
    }

    List<Channel> registeredChannels() {
        return registeredChannels;
    }

    public HarnessGateway gateway() {
        return gateway;
    }

    /** The channel manager for channel lifecycle and outbound delivery. */
    public ChannelManager channelManager() {
        return channelManager;
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private Gateway resolveGateway() {
        if (gateway != null) {
            return gateway;
        }
        throw new IllegalStateException(
                "No gateway available: the main agent has subagents disabled."
                        + " Enable subagents (the default) so a gateway can be created.");
    }

    private static List<Channel> resolveChannels(
            Map<String, Channel> builderChannels, AgentscopeConfig fileConfig) {
        Map<String, ChannelConfigEntry> fileChannels =
                fileConfig.getChannels() != null ? fileConfig.getChannels() : Map.of();

        Map<String, Channel> merged = new LinkedHashMap<>(builderChannels);

        for (Map.Entry<String, ChannelConfigEntry> entry : fileChannels.entrySet()) {
            String channelId = entry.getKey();
            ChannelConfigEntry ce = entry.getValue();
            if (Boolean.TRUE.equals(ce.getDisabled())) {
                merged.remove(channelId);
                continue;
            }
            if (merged.containsKey(channelId)) {
                continue;
            }
            if (ChatUiChannel.CHANNEL_ID.equals(channelId)) {
                merged.put(channelId, ChatUiChannel.create(ce.toChannelConfig(channelId)));
            } else if (DingTalkChannel.TYPE.equals(channelId)) {
                merged.put(
                        channelId,
                        DingTalkChannel.fromProperties(
                                channelId, ce.toChannelConfig(channelId), ce.getProperties()));
            } else if (FeishuChannel.TYPE.equals(channelId)) {
                merged.put(
                        channelId,
                        FeishuChannel.fromProperties(
                                channelId, ce.toChannelConfig(channelId), ce.getProperties()));
            } else {
                log.debug(
                        "Channel '{}' configured in file but no implementation registered via"
                                + " AgentBootstrap.Builder.channel(); skipping auto-creation.",
                        channelId);
            }
        }
        return List.copyOf(merged.values());
    }

    static void applyFileEntry(
            Path cwd, String agentId, AgentConfigEntry e, HarnessAgent.Builder b) {
        String name =
                (e != null && e.getName() != null && !e.getName().isBlank())
                        ? e.getName()
                        : agentId;
        b.name(name);

        if (e != null) {
            if (e.getDescription() != null) {
                b.description(e.getDescription());
            }
            if (e.getSysPrompt() != null) {
                b.sysPrompt(e.getSysPrompt());
            }
            Path workspace =
                    e.getWorkspace() != null && !e.getWorkspace().isBlank()
                            ? cwd.resolve(e.getWorkspace()).normalize()
                            : DEFAULT_WORKSPACE_ROOT;
            b.workspace(workspace);

            if (e.getMaxIters() != null) {
                b.maxIters(e.getMaxIters());
            }
            if (e.getEnvironmentMemory() != null) {
                b.environmentMemory(e.getEnvironmentMemory());
            }
            if (e.getSkillRepository() != null) {
                var repo = SkillRepositorySupport.create(cwd, e.getSkillRepository());
                if (repo != null) {
                    b.skillRepository(repo);
                }
            }
        } else {
            b.workspace(DEFAULT_WORKSPACE_ROOT);
        }
    }

    // -----------------------------------------------------------------
    //  Builder
    // -----------------------------------------------------------------

    public static final class Builder {

        private Path cwd = Paths.get(System.getProperty("user.dir"));
        private Path configPath;
        private boolean skipConfigFile;
        private Model model;
        private AbstractFilesystem globalFileSystem;
        private String mainAgentId;
        private final Map<String, HarnessAgent> prebuilt = new LinkedHashMap<>();
        private final Map<String, Consumer<HarnessAgent.Builder>> configurators =
                new LinkedHashMap<>();
        private final Map<String, Channel> channels = new LinkedHashMap<>();

        private Builder() {}

        public Builder cwd(Path cwd) {
            this.cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            return this;
        }

        public Builder configPath(Path configPath) {
            this.configPath = Objects.requireNonNull(configPath, "configPath");
            return this;
        }

        public Builder skipConfigFile(boolean skip) {
            this.skipConfigFile = skip;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder abstractFilesystem(AbstractFilesystem filesystem) {
            this.globalFileSystem = filesystem;
            return this;
        }

        public Builder mainAgent(String agentId) {
            this.mainAgentId = agentId;
            return this;
        }

        public Builder agent(String agentId, HarnessAgent agent) {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(agent, "agent");
            this.prebuilt.put(agentId, agent);
            return this;
        }

        public Builder configureAgent(String agentId, Consumer<HarnessAgent.Builder> customizer) {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(customizer, "customizer");
            this.configurators.put(agentId, customizer);
            return this;
        }

        public Builder channel(Channel... channels) {
            Objects.requireNonNull(channels, "channels");
            for (Channel c : channels) {
                if (c != null) {
                    this.channels.put(c.channelId(), c);
                }
            }
            return this;
        }

        /**
         * Convenience method: registers the coding and reviewer agents using their respective
         * factories ({@link CodingAgentFactory} / {@link ReviewerAgentFactory}).
         *
         * <p>Applies toolkit and prompt configurators for both agents. Any file-config workspace /
         * maxIters entries still take precedence via {@link #applyFileEntry}.
         *
         * @param codingToolkit toolkit for the coding agent (may be null for empty)
         * @param reviewerToolkit toolkit for the reviewer agent (may be null for empty)
         */
        public Builder withDualCodingAgents(
                io.agentscope.core.tool.Toolkit codingToolkit,
                io.agentscope.core.tool.Toolkit reviewerToolkit) {
            return withDualCodingAgents(codingToolkit, reviewerToolkit, null);
        }

        /**
         * Same as {@link #withDualCodingAgents(io.agentscope.core.tool.Toolkit,
         * io.agentscope.core.tool.Toolkit)} but additionally registers the open-swe-style
         * middleware stack ({@link MessageQueueMiddleware}, {@link ThreadBudgetMiddleware},
         * {@link ModelCallLimitMiddleware}) on both agents when {@code store} is non-null.
         * Without a store the middlewares degrade silently — useful for plain CLI/test usage
         * where no thread-routing exists.
         */
        public Builder withDualCodingAgents(
                io.agentscope.core.tool.Toolkit codingToolkit,
                io.agentscope.core.tool.Toolkit reviewerToolkit,
                BaseStore store) {
            this.configureAgent(
                    "coding",
                    b -> {
                        if (this.model != null) {
                            b.model(this.model);
                        } else {
                            b.model(CodingAgentFactory.buildModel());
                        }
                        if (codingToolkit != null) {
                            b.toolkit(codingToolkit);
                        }
                        b.sysPrompt(
                                io.agentscope.harness.coding.prompt.CodingSystemPrompt.build(
                                        CodingAgentFactory.resolveSandboxWorkingDir(), null));
                        b.maxIters(50);
                        // opencode-style planning: a persistent todo list that is re-injected
                        // every reasoning turn (todo_write + TaskReminderMiddleware). This is the
                        // autonomous-safe planning primitive — no HITL gate, unlike the harness
                        // Plan Mode whose plan_exit ASK has no confirmation handler in this app.
                        b.enableTaskList();
                        // Keep long issue->PR sessions from overflowing the context window.
                        configureCompaction(b);
                        configureSandbox(b);
                        registerOpenSweHooks(b, store);
                    });
            this.configureAgent(
                    "reviewer",
                    b -> {
                        if (this.model != null) {
                            b.model(this.model);
                        } else {
                            b.model(CodingAgentFactory.buildModel());
                        }
                        if (reviewerToolkit != null) {
                            b.toolkit(reviewerToolkit);
                        }
                        b.sysPrompt(
                                io.agentscope.harness.coding.prompt.ReviewerSystemPrompt
                                        .buildTemplate(
                                                CodingAgentFactory.resolveSandboxWorkingDir()));
                        b.maxIters(30);
                        b.disableSubagents();
                        // Large PR diffs can blow the context window; compact long reviews too.
                        configureCompaction(b);
                        configureSandbox(b);
                        registerOpenSweHooks(b, store);
                    });
            return this;
        }

        /**
         * Configures conversation compaction for long-running sessions, mirroring opencode's
         * automatic overflow handling. Was previously only wired in the unused {@link
         * CodingAgentFactory}; this brings it onto the production {@link #withDualCodingAgents}
         * path.
         */
        private static void configureCompaction(HarnessAgent.Builder b) {
            b.compaction(
                    CompactionConfig.builder()
                            .triggerMessages(40)
                            .keepMessages(15)
                            .flushBeforeCompact(true)
                            .build());
        }

        /**
         * Attaches a Docker sandbox filesystem when {@code SANDBOX_TYPE=docker}. Other values (e.g. {@code none}) leave the agent on the default
         * local-host filesystem so unit tests and offline runs continue to work.
         */
        private static void configureSandbox(HarnessAgent.Builder b) {
            String type = CodingAgentFactory.resolveSandboxType();
            if ("docker".equalsIgnoreCase(type)) {
                DockerFilesystemSpec spec = new DockerFilesystemSpec();
                spec.image(CodingAgentFactory.resolveSandboxImage());
                spec.workspaceRoot(CodingAgentFactory.resolveSandboxWorkingDir());
                spec.isolationScope(IsolationScope.SESSION);
                b.filesystem(spec);
            }
        }

        private static void registerOpenSweHooks(HarnessAgent.Builder b, BaseStore store) {
            if (store != null) {
                b.middleware(new MessageQueueMiddleware(store));
            }
            b.middleware(new ThreadBudgetMiddleware(resolveThreadBudget()));
            b.middleware(new ModelCallLimitMiddleware(resolveGlobalModelLimit()));
        }

        private static int resolveThreadBudget() {
            String env = System.getenv("THREAD_MODEL_CALL_BUDGET");
            if (env != null && !env.isBlank()) {
                try {
                    return Integer.parseInt(env.trim());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            return 200;
        }

        private static int resolveGlobalModelLimit() {
            String env = System.getenv("GLOBAL_MODEL_CALL_LIMIT");
            if (env != null && !env.isBlank()) {
                try {
                    return Integer.parseInt(env.trim());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            return 5000;
        }

        /**
         * Assembles all agents and channels, wires the internal gateway, and returns a fully
         * initialized {@link CodingBootstrap}.
         *
         * <h4>Wiring order</h4>
         * <ol>
         *   <li>Resolve agent ids from file config + programmatic registration</li>
         *   <li>For the main agent: extract subagent entries, build shared {@link
         *       DefaultAgentManager} + {@link SessionAgentManager} + {@link HarnessGateway}</li>
         *   <li>Create {@link SessionsTool} from the shared session manager</li>
         *   <li>Build each agent with the shared {@link SessionsTool} injected</li>
         *   <li>Register all agents in the gateway for multi-agent routing</li>
         * </ol>
         */
        public CodingBootstrap build() throws IOException {
            Path resolvedConfig =
                    skipConfigFile ? null : (configPath != null ? configPath : DEFAULT_CONFIG_PATH);
            AgentscopeConfig fileConfig =
                    skipConfigFile
                            ? new AgentscopeConfig()
                            : loadConfigFile(
                                    resolvedConfig != null ? resolvedConfig : DEFAULT_CONFIG_PATH);

            Map<String, AgentConfigEntry> fileAgents =
                    fileConfig.getAgents() != null ? fileConfig.getAgents() : Map.of();

            Set<String> ids = new LinkedHashSet<>();
            ids.addAll(fileAgents.keySet());
            ids.addAll(prebuilt.keySet());
            ids.addAll(configurators.keySet());

            if (ids.isEmpty()) {
                throw new IllegalStateException(
                        "No agents defined: add entries to"
                                + " ~/.agentscope/codingagent/agentscope.json or use"
                                + " AgentBootstrap.builder().agent(id, ...) / configureAgent(...)");
            }

            String main =
                    mainAgentId != null
                            ? mainAgentId
                            : (fileConfig.getMain() != null && !fileConfig.getMain().isBlank()
                                    ? fileConfig.getMain().trim()
                                    : (fileAgents.containsKey(DEFAULT_MAIN_ID)
                                            ? DEFAULT_MAIN_ID
                                            : ids.iterator().next()));

            if (!ids.contains(main)) {
                throw new IllegalStateException(
                        "main agent id '" + main + "' is not among configured agents: " + ids);
            }

            // ---- Phase 1: Build shared session infrastructure ----

            // Configure a temporary builder for the main agent to extract subagent entries.
            Path mainWorkspace = resolveAgentWorkspace(cwd, fileAgents.get(main));
            // Seed bundled skills + subagent templates before subagent entries are scanned, so
            // the general subagent declaration under <workspace>/subagents/ is picked up.
            seedWorkspaceTemplates(mainWorkspace);
            HarnessAgent.Builder mainEntryBuilder = HarnessAgent.builder();
            applyFileEntry(cwd, main, fileAgents.get(main), mainEntryBuilder);
            if (model != null) {
                mainEntryBuilder.model(model);
            }
            Consumer<HarnessAgent.Builder> mainCustomizer = configurators.get(main);
            if (mainCustomizer != null) {
                mainCustomizer.accept(mainEntryBuilder);
            }

            List<SubagentEntry> entries = mainEntryBuilder.buildSubagentEntries(mainWorkspace);

            WorkspaceManager wsManager =
                    globalFileSystem != null
                            ? new WorkspaceManager(mainWorkspace, globalFileSystem)
                            : new WorkspaceManager(mainWorkspace);
            DefaultAgentManager dam = new DefaultAgentManager(entries, wsManager);

            Path storeFile = mainWorkspace.resolve("sessions.json");
            SessionStore sessionStore = new SessionStore(storeFile);
            sessionStore.load();

            SessionAgentManager sam =
                    new SessionAgentManager(
                            dam,
                            AgentManagerConfig.defaults(),
                            new SubagentRunRegistry(),
                            sessionStore);

            ChannelManager channelMgr = new ChannelManager();
            HarnessGateway gateway = HarnessGateway.create(sam, channelMgr);
            TaskRepository taskRepo = new DefaultTaskRepository();
            SessionsTool sessionsTool = new SessionsTool(sam, taskRepo, null, 0);

            // ---- Phase 2: Build agents with SessionsTool injected ----

            Map<String, HarnessAgent> built = new LinkedHashMap<>();

            for (String id : ids) {
                if (prebuilt.containsKey(id)) {
                    built.put(id, prebuilt.get(id));
                    continue;
                }

                AgentConfigEntry entry = fileAgents.get(id);
                if (entry == null && !configurators.containsKey(id)) {
                    continue;
                }

                HarnessAgent.Builder b = HarnessAgent.builder();
                applyFileEntry(cwd, id, entry, b);
                // Seed coding skills into this agent's workspace (idempotent; copy-if-absent).
                seedWorkspaceTemplates(resolveAgentWorkspace(cwd, entry));

                if (model != null) {
                    b.model(model);
                }
                if (globalFileSystem != null) {
                    b.abstractFilesystem(globalFileSystem);
                }

                b.externalSubagentTool(sessionsTool);

                Consumer<HarnessAgent.Builder> c = configurators.get(id);
                if (c != null) {
                    c.accept(b);
                }

                built.put(id, b.build());
            }

            if (!built.containsKey(main)) {
                throw new IllegalStateException(
                        "main agent id '" + main + "' was not built. Built ids: " + built.keySet());
            }

            // ---- Phase 3: Wire gateway with all agents ----

            for (Map.Entry<String, HarnessAgent> e : built.entrySet()) {
                gateway.registerAgent(e.getKey(), e.getValue());
            }
            gateway.bindMainAgent(built.get(main));

            List<Channel> resolvedChannels = resolveChannels(channels, fileConfig);

            log.info(
                    "AgentBootstrap: cwd={}, config={}, main={}, agents={}, channels={}",
                    cwd,
                    skipConfigFile ? "(skipConfigFile)" : resolvedConfig,
                    main,
                    built.keySet(),
                    resolvedChannels.stream().map(Channel::channelId).toList());

            return new CodingBootstrap(
                    cwd,
                    resolvedConfig != null ? resolvedConfig : DEFAULT_CONFIG_PATH,
                    main,
                    Map.copyOf(built),
                    globalFileSystem,
                    resolvedChannels,
                    gateway,
                    channelMgr);
        }

        private static Path resolveAgentWorkspace(Path cwd, AgentConfigEntry entry) {
            if (entry != null && entry.getWorkspace() != null && !entry.getWorkspace().isBlank()) {
                return cwd.resolve(entry.getWorkspace()).normalize();
            }
            return DEFAULT_WORKSPACE_ROOT;
        }

        /**
         * Bundled workspace templates seeded into the agent workspace on first run: opencode-style
         * coding skills (auto-loaded from {@code <workspace>/skills/}) and the general subagent
         * declaration (scanned from {@code <workspace>/subagents/}). Paths are relative to the
         * {@code workspace-templates/} classpath root.
         */
        private static final List<String> WORKSPACE_TEMPLATE_RESOURCES =
                List.of(
                        "skills/verify-changes/SKILL.md",
                        "skills/git-checkpoint/SKILL.md",
                        "skills/apply-patch/SKILL.md",
                        "skills/code-search/SKILL.md",
                        "subagents/general.md");

        /**
         * Copies bundled workspace templates into {@code workspace}, skipping any file that
         * already exists so user edits are never clobbered. Best-effort: failures are logged and
         * do not abort startup.
         */
        private static void seedWorkspaceTemplates(Path workspace) {
            if (workspace == null) {
                return;
            }
            for (String rel : WORKSPACE_TEMPLATE_RESOURCES) {
                Path target = workspace.resolve(rel).normalize();
                if (Files.exists(target)) {
                    continue;
                }
                String resource = "/workspace-templates/" + rel;
                try (InputStream is = CodingBootstrap.class.getResourceAsStream(resource)) {
                    if (is == null) {
                        log.debug("Workspace template not found on classpath: {}", resource);
                        continue;
                    }
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(is, target);
                    log.info("Seeded workspace template {} -> {}", rel, target);
                } catch (IOException e) {
                    log.warn(
                            "Failed to seed workspace template {} -> {}: {}",
                            resource,
                            target,
                            e.getMessage());
                }
            }
        }
    }
}
