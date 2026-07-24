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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards that the system prompt actually tells the LLM which absolute paths it may use, what the
 * active mode does at the boundary, and the location of any extra allow-listed roots. The agent
 * shouldn't have to hit a {@code SecurityException} to learn its own filesystem reach.
 */
class WorkspaceContextMiddlewarePathBoundsTest {

    private final List<WorkspaceManager> openManagers = new ArrayList<>();

    @AfterEach
    void closeOpenManagers() {
        // Release SQLite handles so @TempDir can delete the workspace on Windows.
        for (WorkspaceManager wm : openManagers) {
            wm.close();
        }
        openManagers.clear();
    }

    private WorkspaceManager track(WorkspaceManager wm) {
        openManagers.add(wm);
        return wm;
    }

    @Test
    void localOverlay_promptListsProjectWorkspaceAndModeBoundary(
            @TempDir Path project, @TempDir Path workspace) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        WorkspaceManager wm = track(new WorkspaceManager(workspace, fs));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, null, "BASE\n").block();
        assertNotNull(prompt);
        // Project + Workspace lines
        assertTrue(prompt.contains("Project (the user's source tree"));
        assertTrue(prompt.contains(project.toAbsolutePath().toString()));
        assertTrue(prompt.contains("Workspace (your home base"));
        assertTrue(prompt.contains(workspace.toAbsolutePath().toString()));
        // Mode advertised explicitly
        assertTrue(
                prompt.contains("Path access policy: ROOTED"),
                () -> "Mode boundary not mentioned in prompt: " + prompt);
        // Boundary behaviour stated
        assertTrue(prompt.contains("reject absolute paths outside the roots above"));
        // No additionalRoots configured → no "Additional roots:" line
        assertFalse(prompt.contains("Additional roots:"));
        // Host platform info advertised
        assertTrue(prompt.contains("My operating system is:"));
        assertTrue(prompt.contains("Temporary files directory:"));
    }

    @Test
    void localOverlay_addRoot_isAdvertised(
            @TempDir Path project, @TempDir Path workspace, @TempDir Path shared) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .addRoot(shared)
                        .toFilesystem(workspace, null);
        WorkspaceManager wm = track(new WorkspaceManager(workspace, fs));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, null, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Additional roots: " + shared.toAbsolutePath()));
    }

    @Test
    void localOverlay_unrestrictedMode_describesEscapeHatch(
            @TempDir Path project, @TempDir Path workspace) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec()
                        .project(project)
                        .mode(LocalFsMode.UNRESTRICTED)
                        .toFilesystem(workspace, null);
        WorkspaceManager wm = track(new WorkspaceManager(workspace, fs));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, null, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(
                prompt.contains("UNRESTRICTED"), () -> "UNRESTRICTED mode not surfaced: " + prompt);
    }
}
