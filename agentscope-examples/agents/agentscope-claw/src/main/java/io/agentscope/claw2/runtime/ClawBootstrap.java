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
package io.agentscope.claw2.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.runtime.config.AgentConfigEntry;
import io.agentscope.claw2.runtime.config.AgentscopeConfig;
import io.agentscope.claw2.runtime.config.ChannelConfigEntry;
import io.agentscope.claw2.runtime.config.ChannelTypeRegistry;
import io.agentscope.claw2.runtime.config.SkillRepositorySupport;
import io.agentscope.claw2.runtime.gateway.HarnessGateway;
import io.agentscope.claw2.runtime.outbound.OutboundTool;
import io.agentscope.claw2.runtime.session.AgentManagerConfig;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import io.agentscope.claw2.runtime.session.SessionStore;
import io.agentscope.claw2.runtime.session.SubagentRunRegistry;
import io.agentscope.claw2.runtime.session.tool.SessionsTool;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.DefaultTaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single API class for assembling, configuring, and running the agentscope harness.
 *
 * <h2>Build phase — {@link #builder()}</h2>
 *
 * Loads {@code ${clawHome}/agentscope.json}, merges file-based agent definitions with
 * programmatic {@link Builder} configuration, and produces {@link HarnessAgent} instances wired
 * with {@link SessionsTool} and a shared {@link SessionAgentManager} + {@link HarnessGateway}.
 *
 * <p>{@code clawHome} is the root for all on-disk state (built-in {@code agentscope.json}, custom
 * agents catalog, per-agent workspaces and session stores). It defaults to
 * {@code ~/.agentscope/claw} so multiple harness apps (claw, builder, dataagent, codingagent)
 * each get their own isolated workspace tree.
 *
 * <h2>Runtime phase — two entry points</h2>
 *
 * <ol>
 *   <li>{@link #chatUiChannel()} — obtain a {@link ChatUiChannel} for direct programmatic
 *       interaction (embedded UIs, CLIs, tests). Ready immediately; no {@link #start()} required.
 *   <li>{@link #start()} — initialize and start all pre-registered channel adapters.
 * </ol>
 */
public final class ClawBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ClawBootstrap.class);
    private static final String DEFAULT_MAIN_ID = "default";

    // -----------------------------------------------------------------
    //  Instance state — populated by Builder.build()
    // -----------------------------------------------------------------

    private final Path cwd;
    private final Path configPath;
    private final String mainAgentId;
    private final Map<String, HarnessAgent> agents;
    private final AgentscopeConfig loadedConfig;
    private final List<Channel> registeredChannels;
    private final HarnessGateway gateway;
    private final ChannelManager channelManager;

    private ClawBootstrap(
            Path cwd,
            Path configPath,
            String mainAgentId,
            Map<String, HarnessAgent> agents,
            AgentscopeConfig loadedConfig,
            List<Channel> registeredChannels,
            HarnessGateway gateway,
            ChannelManager channelManager) {
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.mainAgentId = Objects.requireNonNull(mainAgentId, "mainAgentId");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.loadedConfig = loadedConfig != null ? loadedConfig : new AgentscopeConfig();
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

    public static AgentscopeConfig loadConfig(Path clawHome) throws IOException {
        return loadConfigFile(defaultConfigPath(clawHome));
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

    /** The on-disk path of {@code agentscope.json} inside {@code clawHome}. */
    public static Path defaultConfigPath(Path clawHome) {
        return clawHome.resolve("agentscope.json");
    }

    /** Returns the default workspace directory for {@code agentId} under {@code clawHome}. */
    public static Path defaultAgentWorkspace(Path clawHome, String agentId) {
        return clawHome.resolve("agents").resolve(agentId).resolve("workspace").normalize();
    }

    /** Returns the per-agent sessions store file under {@code clawHome}. */
    public static Path defaultSessionsStore(Path clawHome, String agentId) {
        return clawHome.resolve("agents").resolve(agentId).resolve("sessions.json").normalize();
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

    public ClawBootstrap start() {
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
    //  Public / package-private accessors
    // -----------------------------------------------------------------

    /**
     * The claw home directory (basis for relative config paths and the default location for the
     * {@code agentscope.json}, per-agent workspaces and session stores).
     */
    public Path cwd() {
        return cwd;
    }

    /** Alias for {@link #cwd()}; clarifies that this is the claw home root. */
    public Path clawHome() {
        return cwd;
    }

    /** The loaded {@link AgentscopeConfig} from {@code agentscope.json} (or empty if not found). */
    public AgentscopeConfig loadedConfig() {
        return loadedConfig;
    }

    /** All registered agent instances, keyed by agentId. */
    public Map<String, HarnessAgent> agents() {
        return agents;
    }

    String mainAgentId() {
        return mainAgentId;
    }

    HarnessAgent mainAgent() {
        HarnessAgent a = agents.get(mainAgentId);
        if (a == null) {
            throw new IllegalStateException("Main agent not registered: " + mainAgentId);
        }
        return a;
    }

    public Path configPath() {
        return configPath;
    }

    /**
     * Channels resolved from {@code agentscope.json} via {@link ChannelTypeRegistry}, plus any
     * programmatically added via {@link Builder#channel(Channel...)}. These are not yet started
     * — callers either pass them to {@link #start(Channel...)} or register them into the
     * {@link #channelManager()} before calling {@link #start()}.
     */
    public List<Channel> registeredChannels() {
        return registeredChannels;
    }

    public HarnessGateway gateway() {
        return gateway;
    }

    /** The channel manager for channel lifecycle and outbound delivery. */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /**
     * Resolves the workspace {@link Path} for a given agent id using the same logic as the
     * build phase.
     *
     * <p>If {@code agentId} is not found in the loaded config (or has no explicit workspace) the
     * default per-agent workspace path ({@code ${clawHome}/agents/{agentId}/workspace}) is returned.
     */
    public Path resolveWorkspace(String agentId) {
        AgentscopeConfig cfg = loadedConfig;
        AgentConfigEntry entry = cfg.getAgents() != null ? cfg.getAgents().get(agentId) : null;
        if (entry != null && entry.getWorkspace() != null && !entry.getWorkspace().isBlank()) {
            return cwd.resolve(entry.getWorkspace()).normalize();
        }
        return defaultAgentWorkspace(cwd, agentId);
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
            // Look up the factory by `type` (falling back to channelId for backward compatibility
            // with single-instance built-ins like `chatui`).
            String typeId =
                    (ce.getType() != null && !ce.getType().isBlank())
                            ? ce.getType().trim()
                            : channelId;
            Optional<ChannelFactory> factory = ChannelTypeRegistry.get(typeId);
            if (factory.isEmpty()) {
                log.debug(
                        "Channel '{}' (type='{}') configured in file but no factory registered in"
                                + " ChannelTypeRegistry and no programmatic channel supplied;"
                                + " skipping.",
                        channelId,
                        typeId);
                continue;
            }
            try {
                Map<String, Object> props =
                        ce.getProperties() != null ? Map.copyOf(ce.getProperties()) : Map.of();
                Channel created =
                        factory.get().create(channelId, ce.toChannelConfig(channelId), props);
                if (created != null) {
                    merged.put(channelId, created);
                }
            } catch (Exception e) {
                log.error(
                        "Failed to instantiate channel '{}' (type='{}'): {}",
                        channelId,
                        typeId,
                        e.getMessage(),
                        e);
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * Translates the optional {@code session.maintenance} block of {@code agentscope.json} into a
     * runtime {@link AgentManagerConfig}. Falls back to {@link AgentManagerConfig#defaults()} when
     * no block is present.
     */
    private static AgentManagerConfig resolveAgentManagerConfig(AgentscopeConfig fileConfig) {
        var sessionCfg = fileConfig != null ? fileConfig.getSession() : null;
        if (sessionCfg == null || sessionCfg.getMaintenance() == null) {
            return AgentManagerConfig.defaults();
        }
        var m = sessionCfg.getMaintenance();
        String mode = m.getMode();
        if (mode == null || mode.isBlank() || "off".equalsIgnoreCase(mode)) {
            return AgentManagerConfig.defaults();
        }
        long pruneAfterMs = m.pruneAfterMs();
        int maxEntries = m.getMaxEntries() != null ? m.getMaxEntries() : 0;
        io.agentscope.claw2.runtime.session.SessionMaintenanceConfig sm =
                io.agentscope.claw2.runtime.session.SessionMaintenanceConfig.enabled(
                        pruneAfterMs, maxEntries);
        return new AgentManagerConfig(
                AgentManagerConfig.DEFAULT_MAX_SUBAGENT_CONCURRENT,
                AgentManagerConfig.DEFAULT_MAX_NESTED_CONCURRENT,
                true,
                AgentManagerConfig.DEFAULT_MAX_PENDING_ANNOUNCE,
                io.agentscope.claw2.runtime.session.SessionResetPolicy.never(),
                sm);
    }

    static void applyFileEntry(
            Path clawHome, String agentId, AgentConfigEntry e, HarnessAgent.Builder b) {
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
                            ? clawHome.resolve(e.getWorkspace()).normalize()
                            : defaultAgentWorkspace(clawHome, agentId);
            b.workspace(workspace);

            if (e.getMaxIters() != null) {
                b.maxIters(e.getMaxIters());
            }
            if (e.getEnvironmentMemory() != null) {
                b.environmentMemory(e.getEnvironmentMemory());
            }
            if (e.getModel() != null && !e.getModel().isBlank()) {
                b.model(e.getModel());
            }
            for (var entry : e.getEffectiveSkillRepositories()) {
                var repo = SkillRepositorySupport.create(clawHome, entry);
                if (repo != null) {
                    b.skillRepository(repo);
                }
            }
            // identity.name overrides the display name if set
            if (e.getIdentity() != null && e.getIdentity().getName() != null) {
                b.name(e.getIdentity().getName());
            }
        } else {
            b.workspace(defaultAgentWorkspace(clawHome, agentId));
        }
    }

    // -----------------------------------------------------------------
    //  Builder
    // -----------------------------------------------------------------

    public static final class Builder {

        private Path cwd =
                Paths.get(System.getProperty("user.home"), ".agentscope", "claw").toAbsolutePath();
        private Path configPath;
        private boolean skipConfigFile;
        private Model model;
        private String mainAgentId;
        private final Map<String, HarnessAgent> prebuilt = new LinkedHashMap<>();
        private final Map<String, Consumer<HarnessAgent.Builder>> configurators =
                new LinkedHashMap<>();
        private final List<Consumer<HarnessAgent.Builder>> globalConfigurators =
                new java.util.ArrayList<>();
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

        /**
         * Registers a customizer that will be applied to <em>every</em> agent builder, in addition
         * to any per-agent customizers. Useful for injecting cross-cutting concerns like hooks.
         */
        public Builder configureAllAgents(Consumer<HarnessAgent.Builder> customizer) {
            Objects.requireNonNull(customizer, "customizer");
            this.globalConfigurators.add(customizer);
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
         * Assembles all agents and channels, wires the internal gateway, and returns a fully
         * initialized {@link ClawBootstrap}.
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
        public ClawBootstrap build() throws IOException {
            Path resolvedConfig =
                    skipConfigFile
                            ? null
                            : (configPath != null ? configPath : defaultConfigPath(cwd));
            AgentscopeConfig fileConfig =
                    skipConfigFile
                            ? new AgentscopeConfig()
                            : loadConfigFile(
                                    resolvedConfig != null
                                            ? resolvedConfig
                                            : defaultConfigPath(cwd));

            Map<String, AgentConfigEntry> fileAgents =
                    fileConfig.getAgents() != null ? fileConfig.getAgents() : Map.of();

            Set<String> ids = new LinkedHashSet<>();
            ids.addAll(fileAgents.keySet());
            ids.addAll(prebuilt.keySet());
            ids.addAll(configurators.keySet());

            if (ids.isEmpty()) {
                throw new IllegalStateException(
                        "No agents defined: add entries to .agentscope/agentscope.json or use"
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
            Path mainWorkspace = resolveAgentWorkspace(cwd, main, fileAgents.get(main));
            HarnessAgent.Builder mainEntryBuilder = HarnessAgent.builder();
            applyFileEntry(cwd, main, fileAgents.get(main), mainEntryBuilder);
            if (model != null) {
                mainEntryBuilder.model(model);
            }
            Consumer<HarnessAgent.Builder> mainCustomizer = configurators.get(main);
            if (mainCustomizer != null) {
                mainCustomizer.accept(mainEntryBuilder);
            }
            for (Consumer<HarnessAgent.Builder> gc : globalConfigurators) {
                gc.accept(mainEntryBuilder);
            }

            List<SubagentEntry> entries = mainEntryBuilder.buildSubagentEntries(mainWorkspace);

            WorkspaceManager wsManager = new WorkspaceManager(mainWorkspace);
            DefaultAgentManager dam = new DefaultAgentManager(entries, wsManager);

            Path storeFile = defaultSessionsStore(cwd, main);
            SessionStore sessionStore = new SessionStore(storeFile);
            sessionStore.load();

            AgentManagerConfig amCfg = resolveAgentManagerConfig(fileConfig);
            SessionAgentManager sam =
                    new SessionAgentManager(dam, amCfg, new SubagentRunRegistry(), sessionStore);

            ChannelManager channelMgr = new ChannelManager();
            HarnessGateway gateway = HarnessGateway.create(sam, channelMgr);
            TaskRepository taskRepo = new DefaultTaskRepository();
            SessionsTool sessionsTool = new SessionsTool(sam, taskRepo, null, 0);
            OutboundTool outboundTool = new OutboundTool(channelMgr);

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

                if (model != null) {
                    b.model(model);
                }
                b.externalSubagentTool(sessionsTool);

                // Pre-populate this agent's toolkit with the outbound-send tool so the agent can
                // proactively push messages into any registered IM channel. Done before the
                // customizer so callers may still replace the toolkit if they explicitly want to.
                Toolkit agentToolkit = new Toolkit();
                agentToolkit.registerTool(outboundTool);
                b.toolkit(agentToolkit);

                Consumer<HarnessAgent.Builder> c = configurators.get(id);
                if (c != null) {
                    c.accept(b);
                }
                for (Consumer<HarnessAgent.Builder> gc : globalConfigurators) {
                    gc.accept(b);
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

            return new ClawBootstrap(
                    cwd,
                    resolvedConfig != null ? resolvedConfig : defaultConfigPath(cwd),
                    main,
                    Map.copyOf(built),
                    fileConfig,
                    resolvedChannels,
                    gateway,
                    channelMgr);
        }

        private static Path resolveAgentWorkspace(
                Path clawHome, String agentId, AgentConfigEntry entry) {
            if (entry != null && entry.getWorkspace() != null && !entry.getWorkspace().isBlank()) {
                return clawHome.resolve(entry.getWorkspace()).normalize();
            }
            return defaultAgentWorkspace(clawHome, agentId);
        }
    }
}
