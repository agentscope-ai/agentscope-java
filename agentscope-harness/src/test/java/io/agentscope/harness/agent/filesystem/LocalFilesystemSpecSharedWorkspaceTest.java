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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the local-mode host/app read-write asymmetry (#3245): the upper layer
 * applies a per-user namespace prefix to relative paths, so files the host writes to the
 * workspace root were invisible to the agent tools and agent writes landed under
 * {@code {workspace}/{userId}/}.
 */
class LocalFilesystemSpecSharedWorkspaceTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @TempDir Path workspace;
    @TempDir Path project;

    @Test
    void sharedLocalWorkspaceResolvesWorkspaceRootWithoutUserPrefix() throws Exception {
        Path uploaded = workspace.resolve("uploads/result.md");
        Files.createDirectories(uploaded.getParent());
        Files.writeString(uploaded, "host content");

        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .sharedLocalWorkspace(true)
                        .toFilesystem(workspace, rc -> List.of("local-user"));

        ReadResult read = fs.read(RT, "uploads/result.md", 0, 0);
        assertTrue(read.isSuccess(), () -> "host-written file must be readable: " + read.error());
        assertTrue(read.fileData().content().contains("host content"));

        assertTrue(fs.write(RT, "out/agent.md", "from agent").isSuccess());
        assertTrue(Files.isRegularFile(workspace.resolve("out/agent.md")));
        assertFalse(Files.exists(workspace.resolve("local-user")));
    }

    @Test
    void defaultKeepsPerUserPrefix() {
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .toFilesystem(workspace, rc -> List.of("local-user"));

        assertTrue(fs.write(RT, "out/agent.md", "from agent").isSuccess());
        assertTrue(Files.isRegularFile(workspace.resolve("local-user/out/agent.md")));
    }
}
