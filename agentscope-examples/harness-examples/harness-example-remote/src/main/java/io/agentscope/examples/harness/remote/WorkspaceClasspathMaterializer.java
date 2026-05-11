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
package io.agentscope.examples.harness.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Copies {@code classpath:workspace/...} into a temp directory used as the local side of the
 * composite workspace for {@link io.agentscope.harness.agent.filesystem.RemoteFilesystemSpec}.
 */
public final class WorkspaceClasspathMaterializer {

    private static final String[] CLASSPATH_FILES = {
        "classpath:workspace/AGENTS.md",
        "classpath:workspace/skills/query-writing/SKILL.md",
        "classpath:workspace/knowledge/KNOWLEDGE.md",
    };

    private WorkspaceClasspathMaterializer() {}

    /**
     * Materializes bundled workspace resources to disk.
     *
     * @return absolute path to the host workspace directory
     */
    public static Path materialize() {
        try {
            Path dir = Files.createTempDirectory("remote-data-agent-host-workspace-");
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            for (String location : CLASSPATH_FILES) {
                Resource resource = resolver.getResource(location);
                if (!resource.exists()) {
                    throw new IllegalStateException("Missing required resource: " + location);
                }
                String pathWithinWorkspace = location.substring("classpath:workspace/".length());
                Path target = dir.resolve(pathWithinWorkspace);
                Files.createDirectories(target.getParent());
                try (InputStream in = resource.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return dir.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to materialize workspace from classpath", e);
        }
    }
}
