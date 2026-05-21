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

import io.agentscope.core.session.Session;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.DockerFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional sandbox filesystem wiring for the agentscope-claw deployable. When
 * {@code claw.sandbox.enabled=true}, this configuration contributes:
 *
 * <ul>
 *   <li>a {@link SandboxFilesystemSpec} (currently always {@link DockerFilesystemSpec})
 *   <li>a {@link SandboxDistributedOptions} that auto-falls-back to single-node mode
 *       ({@code requireDistributed(false)}) when no distributed {@link Session} bean is present
 * </ul>
 *
 * <p>Both beans are then consumed by {@link ClawAppConfig#clawBootstrap} which applies them to
 * every agent via {@code configureAllAgents}.
 *
 * <h2>Configuration</h2>
 *
 * <pre>{@code
 * claw:
 *   sandbox:
 *     enabled: true                  # off by default
 *     image: agentscope/python-sandbox:py311-slim
 *     network: none                  # docker --network value
 *     workspace-root: /workspace     # path inside container
 *     isolation: USER                # SESSION | USER | AGENT | GLOBAL
 *     projection-roots: AGENTS.md,skills,subagents,knowledge
 *     cpu-count: 1
 *     memory-bytes: 1073741824       # 1 GiB
 * }</pre>
 */
@Configuration
@ConditionalOnProperty(name = "claw.sandbox.enabled", havingValue = "true")
public class SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfig.class);

    @Value("${claw.sandbox.image:agentscope/python-sandbox:py311-slim}")
    private String image;

    @Value("${claw.sandbox.network:none}")
    private String network;

    @Value("${claw.sandbox.workspace-root:/workspace}")
    private String workspaceRoot;

    @Value("${claw.sandbox.isolation:USER}")
    private String isolation;

    @Value("${claw.sandbox.projection-roots:AGENTS.md,skills,subagents,knowledge}")
    private String projectionRoots;

    @Value("${claw.sandbox.cpu-count:0}")
    private long cpuCount;

    @Value("${claw.sandbox.memory-bytes:0}")
    private long memoryBytes;

    @Bean
    public SandboxFilesystemSpec sandboxFilesystemSpec() {
        IsolationScope scope = parseScope(isolation);
        List<String> roots = parseRoots(projectionRoots);

        DockerFilesystemSpec spec = new DockerFilesystemSpec();
        spec.image(image).workspaceRoot(workspaceRoot);
        spec.workspaceProjectionRoots(roots);
        spec.isolationScope(scope);

        if (network != null && !network.isBlank()) {
            spec.network(network);
        }
        if (cpuCount > 0) {
            spec.cpuCount(cpuCount);
        }
        if (memoryBytes > 0) {
            spec.memorySizeBytes(memoryBytes);
        }

        log.info(
                "Sandbox enabled: image={}, network={}, workspaceRoot={}, isolation={},"
                        + " projection={}, cpuCount={}, memoryBytes={}",
                image,
                network,
                workspaceRoot,
                scope,
                roots,
                cpuCount,
                memoryBytes);
        return spec;
    }

    /**
     * Provides {@link SandboxDistributedOptions} for single-node deployments. If a distributed
     * {@link Session} bean is wired (e.g. via {@link SessionBackendConfig}), it is plumbed
     * through; otherwise we set {@code requireDistributed(false)} so the default
     * {@code WorkspaceSession} is accepted.
     *
     * <p>Snapshot persistence is left null in single-node mode; sandbox state still survives
     * restarts via the workspace-relative {@code SessionSandboxStateStore}. For
     * cross-instance survival, wire a {@code RedisSnapshotSpec} or {@code OssSnapshotSpec} via
     * your own override of this bean.
     */
    @Bean
    public SandboxDistributedOptions sandboxDistributedOptions(
            Optional<Session> distributedSession) {
        SandboxDistributedOptions.Builder b = SandboxDistributedOptions.builder();
        if (distributedSession.isPresent()) {
            log.info(
                    "Sandbox running in distributed mode using Session bean of type {}",
                    distributedSession.get().getClass().getSimpleName());
            b.session(distributedSession.get()).requireDistributed(true);
        } else {
            log.info(
                    "Sandbox running in single-node mode (requireDistributed=false). For"
                            + " horizontal scale, expose a RedisSession bean.");
            b.requireDistributed(false);
        }
        return b.build();
    }

    private static IsolationScope parseScope(String raw) {
        if (raw == null || raw.isBlank()) return IsolationScope.SESSION;
        try {
            return IsolationScope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown claw.sandbox.isolation value '"
                            + raw
                            + "'. Expected one of SESSION, USER, AGENT, GLOBAL.",
                    e);
        }
    }

    private static List<String> parseRoots(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String token : Arrays.asList(csv.split(","))) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
