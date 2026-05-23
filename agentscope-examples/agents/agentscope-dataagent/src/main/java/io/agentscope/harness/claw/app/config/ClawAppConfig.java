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
package io.agentscope.harness.claw.app.config;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import io.agentscope.harness.claw.ClawBootstrap;
import io.agentscope.harness.claw.app.auth.UserStore;
import io.agentscope.harness.claw.app.binding.UserBindingStore;
import io.agentscope.harness.claw.app.identity.IdentityLinkStore;
import io.agentscope.harness.claw.app.session.PerUserAgentRegistry;
import io.agentscope.harness.claw.app.session.UserWorkspaceProvisioner;
import io.agentscope.harness.claw.app.toolbus.ToolEventBus;
import io.agentscope.harness.claw.app.toolbus.ToolNotificationHook;
import io.agentscope.harness.claw.app.toolbus.WorkspaceMutationHook;
import io.agentscope.harness.claw.app.util.WorkspaceTemplate;
import io.agentscope.harness.claw.channel.ChannelConfig;
import io.agentscope.harness.claw.channel.DmScope;
import io.agentscope.harness.claw.channel.chatui.ChatUiChannel;
import io.agentscope.harness.claw.config.ChannelConfigEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Spring Boot configuration for the agentscope-claw application.
 *
 * <p>Assembles {@link ClawBootstrap} from {@code .agentscope/agentscope.json}, starts a
 * {@link ChatUiChannel} with per-user session isolation, and wires tool-event streaming.
 *
 * <h2>Model wiring</h2>
 * <ol>
 *   <li>If a {@link Model} bean already exists, it is used as-is.
 *   <li>Otherwise, if {@code claw.dashscope.api-key} is set, a {@link DashScopeChatModel} is
 *       created automatically.
 *   <li>If neither is available, agent calls will fail until a model is configured.
 * </ol>
 */
@Configuration
public class ClawAppConfig {

    private static final Logger log = LoggerFactory.getLogger(ClawAppConfig.class);

    @Value("${claw.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${claw.dashscope.model-name:qwen-max}")
    private String dashscopeModelName;

    @Value("${claw.dashscope.stream:true}")
    private boolean dashscopeStream;

    @Value("${claw.workspace:}")
    private String workspaceDir;

    private final Environment environment;

    public ClawAppConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnProperty(name = "claw.dashscope.api-key")
    public Model clawModel() {
        log.info("Building DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .build();
    }

    @Bean
    public ClawBootstrap clawBootstrap(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            Optional<SandboxFilesystemSpec> sandboxSpec,
            Optional<SandboxDistributedOptions> sandboxDistributed)
            throws IOException {
        Path cwd = resolveCwd();
        ensureAgentscopeConfig(cwd);

        ClawBootstrap.Builder builder = ClawBootstrap.builder().cwd(cwd);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            log.warn(
                    "No model configured. Set claw.dashscope.api-key in application.yml or provide"
                            + " a Model bean. Agent calls will fail until a model is available.");
        }

        builder.configureAllAgents(
                b -> {
                    b.hook(new ToolNotificationHook(toolEventBus));
                    b.hook(new WorkspaceMutationHook());
                    sandboxSpec.ifPresent(b::filesystem);
                    sandboxDistributed.ifPresent(b::sandboxDistributed);
                });

        ClawBootstrap claw = builder.build();

        ChannelConfigEntry ce =
                claw.loadedConfig().getChannels() != null
                        ? claw.loadedConfig().getChannels().get(ChatUiChannel.CHANNEL_ID)
                        : null;
        ChannelConfig chatuiCfg =
                ce != null
                        ? ce.toChannelConfig(ChatUiChannel.CHANNEL_ID)
                        : ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                                .dmScope(DmScope.PER_PEER)
                                .build();
        ChatUiChannel webChannel = ChatUiChannel.create(chatuiCfg);
        claw.start(webChannel);

        log.info(
                "ClawBootstrap initialized: cwd={}, chatui dmScope={}, agents={}",
                cwd,
                chatuiCfg.dmScope(),
                claw.agents().keySet());
        return claw;
    }

    @Bean
    public UserStore userStore(ClawBootstrap claw) {
        Path agentscopeDir = claw.cwd().resolve(".agentscope");
        return new UserStore(agentscopeDir.resolve("users.json"));
    }

    @Bean
    public UserWorkspaceProvisioner userWorkspaceProvisioner(ClawBootstrap claw) {
        Path agentscopeDir = claw.cwd().resolve(".agentscope");
        return new UserWorkspaceProvisioner(agentscopeDir);
    }

    @Value("${claw.runtime.user-agent-idle-ttl-ms:3600000}")
    private long userAgentIdleTtlMs;

    /**
     * Per-user HarnessAgent cache. Each tenant gets its own dedicated agent whose workspace and
     * filesystem are locked at build time to that user's directory; this sidesteps the
     * {@code userIdRef} race that a shared agent would hit once per-user workspaces are wired in.
     */
    @Bean(destroyMethod = "close")
    public PerUserAgentRegistry perUserAgentRegistry(
            ClawBootstrap claw, UserWorkspaceProvisioner provisioner) {
        PerUserAgentRegistry registry =
                new PerUserAgentRegistry(
                        userId ->
                                claw.mainAgentFactory()
                                        .build(userId, provisioner.resolveUserWorkspace(userId)),
                        userAgentIdleTtlMs);
        // Plug the registry into the gateway so per-user turns route to per-user agents.
        claw.gateway().setMainAgentResolver(registry::getOrBuild);
        return registry;
    }

    @Bean
    public UserBindingStore userBindingStore(
            ClawBootstrap claw,
            io.agentscope.harness.claw.session.spi.KvStore<
                            java.util.List<UserBindingStore.UserBinding>>
                    userBindingsKvStore) {
        return new UserBindingStore(userBindingsKvStore);
    }

    /**
     * Default file-backed KV store for user bindings. Overridden by {@link
     * UserBindingsRedisKvStoreConfig} when {@code claw.session.redis.enabled=true}.
     */
    @Bean
    @ConditionalOnMissingBean(name = "userBindingsKvStore")
    public io.agentscope.harness.claw.session.spi.KvStore<
                    java.util.List<UserBindingStore.UserBinding>>
            userBindingsKvStore(ClawBootstrap claw) {
        Path agentscopeDir = claw.cwd().resolve(".agentscope");
        Path usersDir = agentscopeDir.resolve("users");
        com.fasterxml.jackson.core.type.TypeReference<java.util.List<UserBindingStore.UserBinding>>
                typeRef = new com.fasterxml.jackson.core.type.TypeReference<>() {};
        return new io.agentscope.harness.claw.session.spi.FileKvStore<>(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                agentscopeDir,
                typeRef,
                userId -> Path.of("users", userId, "bindings.json"),
                () -> {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    if (!Files.isDirectory(usersDir)) return out;
                    try (var stream = Files.list(usersDir)) {
                        stream.filter(Files::isDirectory)
                                .forEach(p -> out.add(p.getFileName().toString()));
                    } catch (IOException e) {
                        log.warn("Failed to enumerate user bindings: {}", e.getMessage());
                    }
                    return out;
                });
    }

    @Bean
    public IdentityLinkStore identityLinkStore(ClawBootstrap claw) {
        Path agentscopeDir = claw.cwd().resolve(".agentscope");
        return new IdentityLinkStore(agentscopeDir);
    }

    @Bean
    public ChatUiChannel chatUiChannel(ClawBootstrap claw) {
        return (ChatUiChannel)
                claw.channelManager()
                        .getChannel(ChatUiChannel.CHANNEL_ID)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ChatUiChannel not registered in ChannelManager"));
    }

    private Path resolveCwd() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        if (!isDevProfile()) {
            throw new IllegalStateException(
                    "claw.workspace must be set explicitly when running outside the 'dev' profile."
                        + " Falling back to $CWD would silently scatter workspace state across"
                        + " whatever directory the operator happened to start the process in. Set"
                        + " -Dclaw.workspace=/absolute/path or activate the 'dev' profile.");
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private boolean isDevProfile() {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) return true;
        for (String p : active) {
            if ("dev".equalsIgnoreCase(p) || "test".equalsIgnoreCase(p)) return true;
        }
        return false;
    }

    /**
     * Ensures the user has an {@code .agentscope/} workspace by materialising the bundled
     * {@code classpath:/workspace-template/} on first run.
     *
     * <p>The template ships a working example agent ({@code claw}) with two skills
     * ({@code web-research}, {@code code-review}) and two sub-agents ({@code researcher},
     * {@code code-reviewer}). Existing files are never overwritten — users can edit any file
     * under {@code .agentscope/} and it will be preserved across restarts.
     */
    private void ensureAgentscopeConfig(Path cwd) throws IOException {
        Path configDir = cwd.resolve(".agentscope");
        Files.createDirectories(configDir);

        int written = WorkspaceTemplate.materialise(configDir);
        if (written > 0) {
            log.info(
                    "Initialised .agentscope/ from bundled template ({} files): {}",
                    written,
                    configDir);
        }
    }
}
