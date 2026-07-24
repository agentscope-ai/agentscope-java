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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the {@code CompositeFilesystem} and plain {@link LocalFilesystem} (else) branches
 * of {@link WorkspaceContextMiddleware#buildWorkspaceParagraph} that are not exercised by
 * the sandbox or overlay-based tests.
 */
class WorkspaceContextMiddlewareBranchTest {

    private final List<WorkspaceManager> openManagers = new ArrayList<>();

    @AfterEach
    void closeOpenManagers() {
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
    void compositeBranch_includesDistributedDescription(@TempDir Path workspace) {
        AbstractFilesystem local = new LocalFilesystem(workspace, true, 10);
        AbstractFilesystem composite = new CompositeFilesystem(local, Map.of());
        WorkspaceManager wm = track(new WorkspaceManager(workspace, composite));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, null, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Distributed workspace template root"));
        assertTrue(prompt.contains(workspace.toAbsolutePath().toString()));
        assertTrue(prompt.contains("My operating system is:"));
        assertTrue(prompt.contains("Temporary files directory:"));
    }

    @Test
    void elseBranch_plainLocalFilesystem(@TempDir Path workspace) {
        // A plain LocalFilesystem (not wrapped in OverlayFilesystem) hits the else branch.
        AbstractFilesystem fs = new LocalFilesystem(workspace, true, 10);
        WorkspaceManager wm = track(new WorkspaceManager(workspace, fs));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, null, "BASE\n").block();
        assertNotNull(prompt);
        assertTrue(prompt.contains("Your working directory is:"));
        assertTrue(prompt.contains(workspace.toAbsolutePath().toString()));
        assertTrue(prompt.contains("My operating system is:"));
        assertTrue(prompt.contains("Temporary files directory:"));
    }
}
