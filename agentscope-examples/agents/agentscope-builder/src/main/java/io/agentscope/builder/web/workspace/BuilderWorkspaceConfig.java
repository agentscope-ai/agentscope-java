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
package io.agentscope.builder.web.workspace;

import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.harness.agent.store.BaseStore;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the per-tenant workspace filesystem used by all per-agent {@code
 * WorkspaceManager} instances.
 *
 * <p>Each per-agent {@link WorkspaceManagerFactory#forAgent(String, String)} call creates a {@link
 * io.agentscope.harness.agent.filesystem.local.LocalFilesystem} with a {@link
 * io.agentscope.harness.agent.store.NamespaceFactory} that scopes all paths to {@code [users,
 * ownerId, agents, agentId]} within the agent's workspace root.
 */
@Configuration
public class BuilderWorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(BuilderWorkspaceConfig.class);

    @Value("${builder.workspace-store.fs-spec:local}")
    private String fsSpec;

    @Value("${builder.workspace-store.local.max-file-size-mb:10}")
    private int localMaxFileSizeMb;

    @Bean
    public WorkspaceManagerFactory workspaceManagerFactory(
            BuilderBootstrap bootstrap, Optional<BaseStore> remoteStore) {
        Path workspaceRoot = bootstrap.resolveWorkspace(null);
        if ("remote".equals(fsSpec)) {
            if (remoteStore.isEmpty()) {
                throw new IllegalStateException(
                        "builder.workspace-store.fs-spec=remote requires a BaseStore bean."
                                + " Provide one (e.g. via a Redis or OSS store extension).");
            }
            log.info("Builder workspace: remote mode, root={}", workspaceRoot);
            return new WorkspaceManagerFactory(
                    workspaceRoot, localMaxFileSizeMb, remoteStore.get());
        }
        if (!"local".equals(fsSpec) && !"sandbox".equals(fsSpec)) {
            throw new IllegalStateException(
                    "Unknown builder.workspace-store.fs-spec: '"
                            + fsSpec
                            + "'. Expected one of: local, sandbox, remote.");
        }
        log.info(
                "Builder workspace: {} mode, root={}, maxFileSizeMb={}",
                fsSpec,
                workspaceRoot,
                localMaxFileSizeMb);
        return new WorkspaceManagerFactory(workspaceRoot, localMaxFileSizeMb);
    }
}
