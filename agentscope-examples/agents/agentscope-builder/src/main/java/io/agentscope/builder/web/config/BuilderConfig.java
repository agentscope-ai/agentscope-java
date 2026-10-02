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
package io.agentscope.builder.web.config;

import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.runtime.config.ChannelConfigEntry;
import io.agentscope.builder.web.toolbus.ToolEventBus;
import io.agentscope.builder.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.mysql.store.JdbcStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot configuration for the agentscope-builder web module.
 *
 * <p>Assembles a {@link BuilderBootstrap} from {@code .agentscope/agentscope.json} in the working
 * directory (defaults to {@code builder.workspace}), then registers a {@link ChatUiChannel} with
 * {@link DmScope#PER_PEER} so each authenticated user gets an isolated agent session and namespace.
 *
 * <h2>Property prefix</h2>
 *
 * <p>All config keys live under {@code builder.*}. The legacy {@code claw.*} prefix is honored as
 * a fallback so existing deployments keep working; new properties should use {@code builder.*}.
 *
 * <h2>Filesystem topology</h2>
 *
 * <p>Builder is a multi-tenant distributed deployable, so every {@link
 * io.agentscope.harness.agent.HarnessAgent} (the global main agent and every user-custom agent)
 * runs with a {@link RemoteFilesystemSpec} composite filesystem: the per-pod
 * {@code LocalFilesystem} only carries shared/read-only template content, while the {@code
 * memory/}, {@code MEMORY.md}, {@code sessions/}, {@code tasks/}, {@code skills/} and {@code
 * subagents/} routes are persisted through a {@link BaseStore}-backed {@code RemoteFilesystem}
 * shared across pods. The {@link BaseStore} bean is therefore <em>required</em> — operators must
 * provide one (e.g. SQLite/JDBC/Redis-backed); a missing bean fails context refresh fast with a
 * clear message.
 *
 * <h2>Model wiring (priority order)</h2>
 *
 * <ol>
 *   <li>If a {@link Model} Spring Bean is already present (provided by another
 *       {@code @Configuration}), it is used as-is.
 *   <li>Otherwise, if {@code builder.dashscope.api-key} (or {@code claw.dashscope.api-key}) is set,
 *       a {@link DashScopeChatModel} is created automatically.
 *   <li>If neither is available, the app starts without a model (agent calls will fail until one
 *       is configured).
 * </ol>
 *
 * <p>Note: model wiring uses <em>method-parameter</em> injection in {@code @Bean} methods (not
 * field-level {@code @Autowired}) to avoid a circular-dependency with the {@code Model} bean
 * defined in this same class.
 *
 * <h2>Agent config</h2>
 *
 * <p>If {@code ~/.agentscope/builder/agentscope.json} does not exist, a minimal default agent
 * config is auto-generated so the app starts without manual setup.
 */
@Configuration
public class BuilderConfig {

    private static final Logger log = LoggerFactory.getLogger(BuilderConfig.class);

    @Value("${builder.dashscope.api-key:${claw.dashscope.api-key:}}")
    private String dashscopeApiKey;

    @Value("${builder.dashscope.model-name:${claw.dashscope.model-name:qwen-max}}")
    private String dashscopeModelName;

    @Value("${builder.dashscope.stream:${claw.dashscope.stream:true}}")
    private boolean dashscopeStream;

    @Value("${builder.agent.sys-prompt:${claw.agent.sys-prompt:You are a helpful assistant.}}")
    private String agentSysPrompt;

    @Value("${builder.agent.name:${claw.agent.name:builder-agent}}")
    private String agentName;

    @Value("${builder.workspace:${claw.workspace:}}")
    private String workspaceDir;

    // -----------------------------------------------------------------
    //  Model bean — only created when an api-key is set AND no other
    //  Model bean is already present in the context. The conditional
    //  expression treats both `builder.*` and `claw.*` (legacy) as
    //  triggers, and skips the bean when both are blank so that
    //  Optional<Model> injection sites receive Optional.empty().
    // -----------------------------------------------------------------

    /**
     * Creates a {@link DashScopeChatModel} bean when {@code builder.dashscope.api-key} (or the
     * legacy {@code claw.dashscope.api-key}) is configured and no other {@link Model} bean is
     * present. Skipped entirely when both properties are blank so that {@code Optional<Model>}
     * injection sites receive {@code Optional.empty()} instead of a null-valued bean.
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${builder.dashscope.api-key:${claw.dashscope.api-key:}}' != ''")
    public Model dashscopeModel() {
        log.info("Building DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .build();
    }

    // -----------------------------------------------------------------
    //  Default BaseStore — reuses the auto-configured Spring DataSource
    //  (embedded H2 by default; MySQL/PostgreSQL via the `jdbc` profile)
    //  so the app boots out-of-box on a fresh checkout while still
    //  letting operators override with a Redis/SqliteBaseStore/etc. bean.
    // -----------------------------------------------------------------

    /**
     * Default {@link BaseStore} backed by the Spring-managed {@link DataSource}. The schema is
     * auto-created on startup via {@link JdbcStore.Builder#initializeSchema(boolean)}, which is safe
     * across MySQL/PostgreSQL/H2/SQLite. Operators can override by declaring their own
     * {@link BaseStore} bean (Redis, SQLite, etc.).
     */
    @Bean
    @ConditionalOnMissingBean(BaseStore.class)
    public BaseStore baseStore(DataSource dataSource) {
        log.info("Wiring default JdbcStore-backed BaseStore on the Spring DataSource");
        return JdbcStore.builder(dataSource).initializeSchema(true).build();
    }

    // -----------------------------------------------------------------
    //  Core bootstrap — model injected as method parameter (no field
    //  @Autowired) to avoid circular dependency with dashscopeModel() above.
    // -----------------------------------------------------------------

    /**
     * Assembles the {@link BuilderBootstrap}, loading agent config from {@code agentscope.json} and
     * starting the {@link ChatUiChannel} for per-user isolated sessions.
     *
     * <p>Every agent built by the bootstrap is wired with a {@link RemoteFilesystemSpec} (per-user
     * isolation scope) backed by the shared {@link BaseStore}, so memory/sessions/tasks/skills/
     * subagents files persist across pods.
     *
     * @param modelOpt the {@link Model} to use, or empty if none is configured
     * @param toolEventBus the shared tool-event bus for real-time SSE streaming of tool calls
     * @param baseStore the shared {@link BaseStore} that backs every agent's {@code
     *     RemoteFilesystem} routes; <em>required</em> — operators must provide a bean.
     */
    @Bean
    public BuilderBootstrap builderBootstrap(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            BaseStore baseStore,
            Optional<AgentStateStore> sessionOpt)
            throws IOException {
        Path cwd = resolveCwd();
        ensureAgentscopeConfig();

        BuilderBootstrap.Builder builder = BuilderBootstrap.builder().cwd(cwd);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            log.warn(
                    "No model configured. Set builder.dashscope.api-key in application.yml or"
                            + " provide a Model bean. Agent calls will fail until a model is"
                            + " available.");
        }

        AgentStateStore stateStore = sessionOpt.orElseGet(InMemoryAgentStateStore::new);
        if (sessionOpt.isEmpty()) {
            log.warn(
                    "No distributed AgentStateStore bean configured ({}); using"
                            + " InMemoryAgentStateStore. For multi-replica deployments, provide"
                            + " a distributed AgentStateStore bean"
                            + " (e.g. from agentscope-extensions-redis).",
                    AgentStateStore.class.getName());
        }

        // RemoteFilesystemSpec requires a distributed AgentStateStore; when the effective store is
        // local (InMemory/JsonFile), use LocalFilesystemSpec instead so the harness won't reject
        // the topology at build time.
        boolean localStore = isLocalStateStore(stateStore);
        if (localStore) {
            log.info(
                    "Effective AgentStateStore is local ({}); using LocalFilesystemSpec.",
                    stateStore.getClass().getSimpleName());
        } else {
            log.info(
                    "Effective AgentStateStore is distributed ({}); using RemoteFilesystemSpec.",
                    stateStore.getClass().getSimpleName());
        }

        builder.configureAllAgents(
                b -> {
                    b.middleware(new ToolNotificationMiddleware(toolEventBus));
                    b.stateStore(stateStore);
                    if (localStore) {
                        b.filesystem(new LocalFilesystemSpec().isolationScope(IsolationScope.USER));
                    } else {
                        b.filesystem(
                                new RemoteFilesystemSpec(baseStore)
                                        .isolationScope(IsolationScope.USER)
                                        .addSharedPrefix("activity/"));
                    }
                });

        BuilderBootstrap bootstrap = builder.build();

        // Build the chatui channel using the file-config's bindings & dmScope (if any),
        // so admin-edited bindings in agentscope.json are honored. Falls back to PER_PEER
        // when no chatui entry exists.
        ChannelConfigEntry ce =
                bootstrap.loadedConfig().getChannels() != null
                        ? bootstrap.loadedConfig().getChannels().get(ChatUiChannel.CHANNEL_ID)
                        : null;
        ChannelConfig chatuiCfg =
                ce != null
                        ? ce.toChannelConfig(ChatUiChannel.CHANNEL_ID)
                        : ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                                .dmScope(DmScope.PER_PEER)
                                .build();
        ChatUiChannel webChannel = ChatUiChannel.create(chatuiCfg);
        bootstrap.start(webChannel);

        log.info(
                "BuilderBootstrap initialized: cwd={}, chatui dmScope={}, bindings={}",
                cwd,
                chatuiCfg.dmScope(),
                chatuiCfg.bindings().size());
        return bootstrap;
    }

    @Bean
    public io.agentscope.builder.web.identity.IdentityLinkStore identityLinkStore(
            BuilderBootstrap bootstrap) {
        Path agentscopeDir = bootstrap.cwd().resolve(".agentscope");
        return new io.agentscope.builder.web.identity.IdentityLinkStore(agentscopeDir);
    }

    @Bean
    public ChatUiChannel chatUiChannel(BuilderBootstrap bootstrap) {
        return (ChatUiChannel)
                bootstrap
                        .channelManager()
                        .getChannel(ChatUiChannel.CHANNEL_ID)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ChatUiChannel not registered in ChannelManager"));
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    /**
     * Returns {@code true} when the given store is a local, in-process implementation that cannot
     * be shared across pods, i.e. {@link InMemoryAgentStateStore} or {@link JsonFileAgentStateStore}.
     * When this returns {@code true}, the harness must use {@link LocalFilesystemSpec} instead of
     * {@link RemoteFilesystemSpec}.
     */
    private static boolean isLocalStateStore(AgentStateStore store) {
        return store instanceof InMemoryAgentStateStore || store instanceof JsonFileAgentStateStore;
    }

    private Path resolveCwd() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * Auto-generates a minimal {@code ~/.agentscope/builder/agentscope.json} if it doesn't exist,
     * so the app can start without manual setup. The generated config defines a single
     * {@code default} agent using the configured system prompt and lets the bootstrap fall through
     * to {@link BuilderBootstrap#DEFAULT_WORKSPACE_ROOT} for the workspace location.
     *
     * <p>The workspace root is the read-only shared layer of the composite filesystem (template
     * content, default {@code AGENTS.md} / {@code skills/} / {@code subagents/} / {@code knowledge/}
     * shipped on disk). User-writable routes are persisted through the {@link BaseStore}.
     */
    private void ensureAgentscopeConfig() throws IOException {
        Path configFile = BuilderBootstrap.DEFAULT_CONFIG_PATH;
        Path workspaceRoot = BuilderBootstrap.DEFAULT_WORKSPACE_ROOT;

        if (Files.exists(configFile)) {
            return;
        }

        Files.createDirectories(configFile.getParent());
        Files.createDirectories(workspaceRoot);

        String agentsJson =
                """
                {
                  "main": "default",
                  "agents": {
                    "default": {
                      "name": "%s",
                      "sysPrompt": "%s"
                    }
                  }
                }
                """
                        .formatted(
                                agentName.replace("\"", "\\\""),
                                agentSysPrompt.replace("\"", "\\\"").replace("\n", "\\n"));

        Files.writeString(configFile, agentsJson);
        log.info("Auto-generated agent config at {}", configFile);

        io.agentscope.builder.web.scaffold.WorkspaceScaffolder.scaffold(
                workspaceRoot, agentName, agentSysPrompt);
    }
}
