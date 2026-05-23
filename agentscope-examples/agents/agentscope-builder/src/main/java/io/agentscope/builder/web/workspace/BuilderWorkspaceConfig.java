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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the per-tenant workspace filesystem used by all per-agent {@code
 * WorkspaceManager} instances.
 *
 * <p>Builder is a multi-tenant distributed deployable, so per-agent workspaces always run on a
 * composite filesystem: a read-only {@link
 * io.agentscope.harness.agent.filesystem.local.LocalFilesystem} over the shared workspace root
 * (templates, default {@code AGENTS.md} / {@code skills/} / {@code subagents/} / {@code knowledge/}
 * shipped on disk) blended with a per-(owner, agent) {@link
 * io.agentscope.harness.agent.filesystem.RemoteFilesystem} for {@code memory/}, {@code MEMORY.md},
 * {@code sessions/}, {@code tasks/}, {@code skills/} and {@code subagents/} routes. The {@link
 * BaseStore} bean backing the remote routes is therefore required — operators must wire one.
 */
@Configuration
public class BuilderWorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(BuilderWorkspaceConfig.class);

    @Value("${builder.workspace-store.local.max-file-size-mb:10}")
    private int localMaxFileSizeMb;

    @Bean
    public WorkspaceManagerFactory workspaceManagerFactory(
            BuilderBootstrap bootstrap, BaseStore baseStore) {
        Path workspaceRoot = bootstrap.resolveWorkspace(null);
        log.info(
                "Builder workspace: composite mode, root={}, maxFileSizeMb={}, store={}",
                workspaceRoot,
                localMaxFileSizeMb,
                baseStore.getClass().getSimpleName());
        return new WorkspaceManagerFactory(workspaceRoot, localMaxFileSizeMb, baseStore);
    }
}
