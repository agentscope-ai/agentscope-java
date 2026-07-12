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
package io.agentscope.harness.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpServerRegistrarTest {

    @TempDir Path workspace;

    @Test
    void resolvesWorkspaceRelativeStdioWorkingDirectory() {
        WorkspaceManager manager = new WorkspaceManager(workspace);

        assertEquals(
                workspace.toAbsolutePath().normalize(),
                McpServerRegistrar.resolveStdioWorkingDirectory(manager, "."));
        assertEquals(
                workspace.resolve("tools/server").toAbsolutePath().normalize(),
                McpServerRegistrar.resolveStdioWorkingDirectory(manager, "tools/server"));
        assertNull(McpServerRegistrar.resolveStdioWorkingDirectory(manager, null));
    }

    @Test
    void rejectsStdioWorkingDirectoryOutsideWorkspace() {
        WorkspaceManager manager = new WorkspaceManager(workspace);

        assertThrows(
                IllegalArgumentException.class,
                () -> McpServerRegistrar.resolveStdioWorkingDirectory(manager, "../outside"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        McpServerRegistrar.resolveStdioWorkingDirectory(
                                manager, workspace.toAbsolutePath().toString()));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpServerRegistrar.resolveStdioWorkingDirectory(null, "."));
    }
}
