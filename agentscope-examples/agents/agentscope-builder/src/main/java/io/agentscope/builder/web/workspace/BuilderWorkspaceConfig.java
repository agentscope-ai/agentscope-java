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
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for shared workspace path resolution.
 *
 * <p>Filesystem composition for each agent lives inside the harness via {@code
 * HarnessAgent#workspaceFor(String, String)} and is selected by {@code
 * builder.workspace-store.fs-spec} in {@code BuilderConfig}. The only thing left in this module is
 * the shared on-disk root resolver used by platform side-channels (the per-user marketplaces
 * git-clone cache and per-agent workspace scaffolding).
 */
@Configuration
public class BuilderWorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(BuilderWorkspaceConfig.class);

    @Bean
    public SharedWorkspacePaths sharedWorkspacePaths(BuilderBootstrap bootstrap) {
        Path workspaceRoot = bootstrap.resolveWorkspace(null);
        log.info("Builder shared workspace root: {}", workspaceRoot);
        return new SharedWorkspacePaths(workspaceRoot);
    }
}
