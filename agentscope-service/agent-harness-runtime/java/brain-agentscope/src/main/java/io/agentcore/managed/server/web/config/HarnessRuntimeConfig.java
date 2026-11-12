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
package io.agentcore.managed.server.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedSessionLogStore;
import io.agentcore.managed.server.runtime.managed.RemoteWorkspaceStore;
import io.agentcore.managed.server.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean assembly for the builder data plane.
 *
 * <ul>
 *   <li>{@link ObjectMapper} — shared JSON helper when Jackson auto-configuration does not expose
 *       one (WebFlux / Boot 4 setups);
 *   <li>{@link Model} — optional default DashScope model for agents whose snapshot does not pin a
 *       model name; skipped when no api-key is configured so {@code Optional<Model>} injection
 *       sites receive {@code Optional.empty()};
 *   <li>{@link BaseStore} — Session Service private workspace storage. Definition
 *       files are materialized from the admitted execution snapshot;
 *   <li>{@link AgentStateStore} — legacy-only storage, absent from Managed native Session assembly;
 *   <li>{@link SharedWorkspacePaths} — resolves the platform-wide shared workspace root
 *       (identical layout to the control plane).
 * </ul>
 */
@Configuration
public class HarnessRuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(HarnessRuntimeConfig.class);

    @Value("${managed-agents.dashscope.api-key:${claw.dashscope.api-key:}}")
    private String dashscopeApiKey;

    @Value("${managed-agents.dashscope.model-name:${claw.dashscope.model-name:qwen-max}}")
    private String dashscopeModelName;

    @Value("${managed-agents.dashscope.stream:${claw.dashscope.stream:true}}")
    private boolean dashscopeStream;

    /**
     * Ensures a shared {@link ObjectMapper} is available for {@code ManagedJsonHelper} and API
     * layers when Jackson auto-configuration does not expose one (WebFlux / Boot 4 setups).
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Creates a {@link DashScopeChatModel} bean when {@code builder.dashscope.api-key} (or the
     * legacy {@code claw.dashscope.api-key}) is configured and no other {@link Model} bean is
     * present.
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression(
            "'${managed-agents.dashscope.api-key:${claw.dashscope.api-key:}}' != ''")
    public Model dashscopeModel() {
        log.info("Building DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .build();
    }

    /**
     * Default {@link BaseStore} backed by Session Service's transactional private workspace.
     * Operators can override by declaring their own {@link BaseStore} bean.
     */
    @Bean
    @ConditionalOnMissingBean(BaseStore.class)
    public BaseStore baseStore(SessionServiceClient client, ObjectMapper mapper) {
        return new RemoteWorkspaceStore(client, mapper);
    }

    /** Native writes are committed through the active HTTP execution; readers require explicit scope. */
    @Bean
    @ConditionalOnMissingBean(SessionLogStore.class)
    public ManagedSessionLogStore sessionLogStore(ManagedJournalStore journal) {
        return new ManagedSessionLogStore(journal);
    }

    /**
     * Shared workspace path resolver. Uses the same platform-wide root as the control plane
     * ({@code ~/.managed-agents/workspaces} by default) so per-agent data paths line up across
     * planes.
     */
    @Bean
    @ConditionalOnMissingBean(SharedWorkspacePaths.class)
    public SharedWorkspacePaths sharedWorkspacePaths(
            @Value("${managed-agents.workspace-root:${user.home}/.managed-agents/workspaces}")
                    String workspaceRoot) {
        Path root = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        log.info("Builder shared workspace root: {}", root);
        return new SharedWorkspacePaths(root);
    }
}
