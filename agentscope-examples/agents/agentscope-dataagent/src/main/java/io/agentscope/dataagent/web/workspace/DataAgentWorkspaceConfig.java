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
package io.agentscope.dataagent.web.workspace;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the per-tenant workspace filesystem used by every per-agent
 * {@code WorkspaceManager}.
 *
 * <p>DataAgent is a multi-tenant deployable. Both the browser workspace controllers and the agent
 * runtime read/write through one live Docker {@link
 * io.agentscope.harness.agent.sandbox.Sandbox} per {@code (userId, agentId)} owned by
 * {@link UserSandboxRegistry}. This is what makes the workspace user-isolated — every other route
 * the old {@code CompositeFilesystem} fell through to a shared {@code LocalFilesystem}, which
 * leaked content across tenants.
 *
 * <p>The shared, read-only seed content (AGENTS.md / skills/ / subagents/ / knowledge/) lives
 * under {@code ${cwd}/shared/} on the host and is projected into every fresh container via the
 * registry's {@code __workspace_projection__} entry.
 *
 * <p>Multi-replica deployments must use sticky load-balancing by {@code userId} so a user's
 * traffic lands on the same pod — the registry is in-memory only, and two pods otherwise spin up
 * independent containers for the same user.
 */
@Configuration
public class DataAgentWorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentWorkspaceConfig.class);

    @Value("${dataagent.sandbox.idle-ttl-min:15}")
    private long idleTtlMinutes;

    @Value("${dataagent.sandbox.eviction-poll-sec:60}")
    private long evictionPollSeconds;

    /**
     * Default {@link SandboxClient} bean — a no-arg {@link DockerSandboxClient}. Operators can
     * override by declaring their own {@code SandboxClient<DockerSandboxClientOptions>} bean.
     */
    @Bean
    @ConditionalOnMissingBean(SandboxClient.class)
    public SandboxClient<DockerSandboxClientOptions> sandboxClient() {
        log.info("Wiring default DockerSandboxClient for per-user workspace sandboxes");
        return new DockerSandboxClient();
    }

    @Bean
    public UserSandboxRegistry userSandboxRegistry(
            SandboxClient<DockerSandboxClientOptions> sandboxClient, DataAgentBootstrap bootstrap) {
        Path sharedRoot = bootstrap.cwd().resolve("shared");
        Duration idleTtl = Duration.ofMinutes(idleTtlMinutes);
        Duration evictionPoll = Duration.ofSeconds(evictionPollSeconds);
        log.info(
                "DataAgent sandbox registry: hostWorkspaceRoot={}, idleTtl={}, evictionPoll={}",
                sharedRoot,
                idleTtl,
                evictionPoll);
        return new UserSandboxRegistry(sandboxClient, sharedRoot, idleTtl, evictionPoll);
    }

    @Bean
    public WorkspaceManagerFactory workspaceManagerFactory(UserSandboxRegistry registry) {
        return new WorkspaceManagerFactory(registry);
    }
}
