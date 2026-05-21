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
import io.agentscope.builder.runtime.channel.ChannelConfig;
import io.agentscope.builder.runtime.channel.DmScope;
import io.agentscope.builder.runtime.channel.chatui.ChatUiChannel;
import io.agentscope.builder.runtime.config.ChannelConfigEntry;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.toolbus.ToolEventBus;
import io.agentscope.builder.web.toolbus.ToolNotificationHook;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
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
 * <p>If {@code .agentscope/agentscope.json} does not exist in the working directory, a minimal
 * default agent config is auto-generated so the app starts without manual setup.
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
    //  Core bootstrap — model injected as method parameter (no field
    //  @Autowired) to avoid circular dependency with dashscopeModel() above.
    // -----------------------------------------------------------------

    /**
     * Assembles the {@link BuilderBootstrap}, loading agent config from {@code agentscope.json} and
     * starting the {@link ChatUiChannel} for per-user isolated sessions.
     *
     * @param modelOpt the {@link Model} to use, or empty if none is configured
     * @param toolEventBus the shared tool-event bus for real-time SSE streaming of tool calls
     * @param sandboxSpec the sandbox filesystem spec, present when {@code builder.sandbox.enabled}
     * @param sandboxDistributed sandbox distributed options, present when sandbox is enabled
     */
    @Bean
    public BuilderBootstrap builderBootstrap(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            Optional<SandboxFilesystemSpec> sandboxSpec,
            Optional<SandboxDistributedOptions> sandboxDistributed)
            throws IOException {
        Path cwd = resolveCwd();
        ensureAgentscopeConfig(cwd);

        BuilderBootstrap.Builder builder = BuilderBootstrap.builder().cwd(cwd);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            log.warn(
                    "No model configured. Set builder.dashscope.api-key in application.yml or"
                            + " provide a Model bean. Agent calls will fail until a model is"
                            + " available.");
        }

        builder.configureAllAgents(
                b -> {
                    b.hook(new ToolNotificationHook(toolEventBus));
                    sandboxSpec.ifPresent(b::filesystem);
                    sandboxDistributed.ifPresent(b::sandboxDistributed);
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
    public UserAgentDefinitionStore userAgentDefinitionStore(BuilderBootstrap bootstrap) {
        Path agentscopeDir = bootstrap.cwd().resolve(".agentscope");
        return new UserAgentDefinitionStore(agentscopeDir);
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

    private Path resolveCwd() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * Auto-generates a minimal {@code .agentscope/agentscope.json} if it doesn't exist, so the
     * app can start without manual setup. The generated config defines a single {@code default}
     * agent using the configured system prompt.
     */
    private void ensureAgentscopeConfig(Path cwd) throws IOException {
        Path configDir = cwd.resolve(".agentscope");
        Path configFile = configDir.resolve("agentscope.json");
        Path workspace = configDir.resolve("workspace");

        if (Files.exists(configFile)) {
            return;
        }

        Files.createDirectories(workspace);

        String agentsJson =
                """
                {
                  "main": "default",
                  "agents": {
                    "default": {
                      "name": "%s",
                      "sysPrompt": "%s",
                      "workspace": ".agentscope/workspace"
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
                workspace, agentName, agentSysPrompt);
    }
}
