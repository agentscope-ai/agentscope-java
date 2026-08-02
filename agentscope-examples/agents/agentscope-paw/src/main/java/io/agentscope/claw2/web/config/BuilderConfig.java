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
package io.agentscope.claw2.web.config;

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.ChannelConfigEntry;
import io.agentscope.claw2.web.scaffold.WorkspaceScaffolder;
import io.agentscope.claw2.web.toolbus.ToolEventBus;
import io.agentscope.claw2.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.transcript.FilesystemTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptStore;
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
 * Spring Boot configuration for agentscope-claw — the local single-user assistant.
 *
 * <p>Assembles a {@link ClawBootstrap} rooted at {@code ${claw.home}} (defaults to
 * {@code ~/.agentscope/claw}). The {@code agentscope.json} file defines the built-in agents; if it
 * does not exist, a minimal default agent is auto-generated so the app starts without manual
 * setup.
 *
 * <h2>Model wiring (priority order)</h2>
 *
 * <ol>
 *   <li>If a {@link Model} Spring Bean is already present (provided by another
 *       {@code @Configuration}), it is used as-is.
 *   <li>Otherwise, if {@code claw.dashscope.api-key} is set, a {@link DashScopeChatModel} is
 *       created automatically.
 *   <li>If neither is available, the app starts without a model (agent calls will fail until one
 *       is configured).
 * </ol>
 */
@Configuration
public class BuilderConfig {

    private static final Logger log = LoggerFactory.getLogger(BuilderConfig.class);

    @Value("${claw.home:#{systemProperties['user.home']}/.agentscope/claw}")
    private String clawHome;

    @Value("${claw.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${claw.dashscope.model-name:qwen-max}")
    private String dashscopeModelName;

    @Value("${claw.dashscope.stream:true}")
    private boolean dashscopeStream;

    @Value("${claw.agent.name:claw}")
    private String agentName;

    @Value(
            "${claw.agent.sys-prompt:You are a helpful local assistant. Answer accurately and"
                    + " concisely.}")
    private String agentSysPrompt;

    /**
     * Shared filesystem root for segmented session transcripts. aistiod can read the same tree via
     * {@code AISTIO_TRANSCRIPT_FS_ROOT}. Empty disables the explicit store (HarnessAgent still
     * defaults to {@code workspace/.agentscope/transcripts} when a filesystem is absent).
     */
    @Value("${claw.transcript.root:}")
    private String transcriptRoot;

    /** Tenant / namespace segment in transcript keys — keep in sync with {@code claw.aistio.namespace}. */
    @Value("${claw.transcript.tenant:${claw.aistio.namespace:default}}")
    private String transcriptTenant;

    // -----------------------------------------------------------------
    //  Model bean — only created when an api-key is set AND no other
    //  Model bean is already present in the context.
    // -----------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${claw.dashscope.api-key:}' != ''")
    public Model dashscopeModel() {
        log.info("Building DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .build();
    }

    /**
     * Shared segmented transcript store. Same root should be passed to aistiod as
     * {@code AISTIO_TRANSCRIPT_FS_ROOT} for Operate message history without a live DP.
     */
    @Bean
    @ConditionalOnExpression("'${claw.transcript.enabled:true}' == 'true'")
    public TranscriptStore pawTranscriptStore() throws IOException {
        Path home = resolveClawHome();
        Path root =
                (transcriptRoot != null && !transcriptRoot.isBlank())
                        ? resolvePath(transcriptRoot)
                        : home.resolve("transcripts");
        Files.createDirectories(root);
        String tenant =
                transcriptTenant != null && !transcriptTenant.isBlank()
                        ? transcriptTenant
                        : "default";
        log.info("Session transcript store: root={}, tenant={}", root, tenant);
        return new FilesystemTranscriptStore(root);
    }

    @Bean
    public String pawTranscriptTenant() {
        return transcriptTenant != null && !transcriptTenant.isBlank()
                ? transcriptTenant
                : "default";
    }

    // -----------------------------------------------------------------
    //  Core bootstrap — model injected as method parameter to avoid the
    //  circular dependency that would occur with field-level @Autowired.
    // -----------------------------------------------------------------

    @Bean
    public ClawBootstrap builderBootstrap(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            Optional<AgentScopeAdapter> aistioAdapter,
            Optional<TranscriptStore> transcriptStore,
            String pawTranscriptTenant)
            throws IOException {
        Path home = resolveClawHome();
        ensureAgentscopeConfig(home);

        ClawBootstrap.Builder builder = ClawBootstrap.builder().cwd(home);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            // Fail fast at startup with an actionable message — without a model every chat
            // request later NPEs deep inside ReActAgent on Model.getModelName(), which is
            // confusing to diagnose. Surface the misconfiguration here instead.
            throw new IllegalStateException(
                    "agentscope-claw cannot start without a model.\n"
                            + "  Configure one of the following before launching:\n"
                            + "    * set the DASHSCOPE_API_KEY environment variable, or\n"
                            + "    * set claw.dashscope.api-key in application.yml, or\n"
                            + "    * register your own io.agentscope.core.model.Model"
                            + " Spring @Bean.\n"
                            + "  See claw.dashscope.model-name (default: qwen-max) to override the"
                            + " model id.");
        }

        builder.configureAllAgents(b -> b.middleware(new ToolNotificationMiddleware(toolEventBus)));

        transcriptStore.ifPresent(
                store ->
                        builder.configureAllAgents(
                                b ->
                                        b.transcriptStore(store)
                                                .transcriptTenant(pawTranscriptTenant)));

        // A ReActAgent's middleware list is fixed at build time, so aistio has to be wired in here
        // rather than when the bridge attaches — without it there is no session to observe.
        aistioAdapter.ifPresent(
                adapter -> builder.configureAllAgents(b -> b.middleware(adapter.middleware())));

        ClawBootstrap bootstrap = builder.build();

        // Build the chatui channel. Honor file-config bindings & dmScope when present, otherwise
        // default to a single shared session (DmScope.MAIN) since this is a local single-user app.
        ChannelConfigEntry ce =
                bootstrap.loadedConfig().getChannels() != null
                        ? bootstrap.loadedConfig().getChannels().get(ChatUiChannel.CHANNEL_ID)
                        : null;
        ChannelConfig chatuiCfg =
                ce != null
                        ? ce.toChannelConfig(ChatUiChannel.CHANNEL_ID)
                        : ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                                .dmScope(DmScope.MAIN)
                                .build();
        ChatUiChannel webChannel = ChatUiChannel.create(chatuiCfg);

        // Pre-register file-resolved channels (dingtalk, wecom, …) into the channel manager so
        // bootstrap.start(webChannel) starts them alongside the always-on chatui channel.
        for (io.agentscope.harness.agent.gateway.channel.Channel ch :
                bootstrap.registeredChannels()) {
            if (ch != null && !ChatUiChannel.CHANNEL_ID.equals(ch.channelId())) {
                bootstrap.channelManager().register(ch);
            }
        }
        bootstrap.start(webChannel);

        log.info(
                "ClawBootstrap initialized: home={}, chatui dmScope={}, bindings={}, channels={}",
                home,
                chatuiCfg.dmScope(),
                chatuiCfg.bindings().size(),
                bootstrap.channelManager().channelIds());
        return bootstrap;
    }

    @Bean
    public ChatUiChannel chatUiChannel(ClawBootstrap bootstrap) {
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

    private Path resolveClawHome() {
        String raw = clawHome != null && !clawHome.isBlank() ? clawHome : "~/.agentscope/claw";
        return resolvePath(raw);
    }

    private static Path resolvePath(String raw) {
        String expanded = raw;
        if (expanded.startsWith("~")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        return Paths.get(expanded).toAbsolutePath().normalize();
    }

    /**
     * Auto-generates a minimal {@code agentscope.json} (and scaffolds the default agent
     * workspace) if it doesn't already exist. The generated config defines a single
     * {@code default} built-in agent with an explicit {@code workspace} of {@code "workspace"},
     * resolving to {@code ${clawHome}/workspace} (i.e. {@code ~/.agentscope/claw/workspace} by
     * default) so the main HarnessAgent's tree is right under {@code clawHome} rather than
     * buried beneath {@code agents/default/workspace}.
     */
    private void ensureAgentscopeConfig(Path home) throws IOException {
        Path configFile = home.resolve("agentscope.json");
        Path defaultWorkspace = home.resolve("workspace").normalize();

        if (Files.exists(configFile)) {
            return;
        }
        Files.createDirectories(home);
        Files.createDirectories(defaultWorkspace);

        String agentsJson =
                """
                {
                  "main": "default",
                  "agents": {
                    "default": {
                      "name": "%s",
                      "workspace": "workspace",
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

        WorkspaceScaffolder.scaffold(defaultWorkspace, agentName, agentSysPrompt);
    }
}
