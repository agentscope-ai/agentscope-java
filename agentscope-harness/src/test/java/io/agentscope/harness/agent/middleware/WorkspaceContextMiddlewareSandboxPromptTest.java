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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards that the sandbox branch of the workspace paragraph names the actual artifact delivery
 * tool ({@code deliver_artifact}) when one is configured, and never references the non-existent
 * "upload/download tools".
 */
class WorkspaceContextMiddlewareSandboxPromptTest {

    private AbstractSandboxFilesystem mockSandboxFilesystem() {
        AbstractSandboxFilesystem fs = mock(AbstractSandboxFilesystem.class);
        when(fs.id()).thenReturn("sandbox-test");
        when(fs.read(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(ReadResult.success(new FileData("", "utf-8")));
        when(fs.glob(any(), anyString(), anyString())).thenReturn(GlobResult.success(List.of()));
        return fs;
    }

    private String prompt(WorkspaceManager wm, boolean artifactDeliveryEnabled) {
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);
        mw.setArtifactDeliveryEnabled(artifactDeliveryEnabled);
        return mw.onSystemPrompt(null, null, "BASE\n").block();
    }

    @Test
    void sandboxPrompt_withoutDeliveryTool_admitsNoBoundaryMechanism(@TempDir Path workspace) {
        try (WorkspaceManager wm = new WorkspaceManager(workspace, mockSandboxFilesystem())) {
            String prompt = prompt(wm, false);

            assertNotNull(prompt);
            assertTrue(prompt.contains("Sandbox root: /workspace"));
            assertTrue(prompt.contains("no mechanism for moving files across the boundary"));
            assertFalse(prompt.contains("deliver_artifact"), () -> prompt);
            assertFalse(prompt.contains("upload/download tools"), () -> prompt);
        }
    }

    @Test
    void sandboxPrompt_withDeliveryTool_namesIt(@TempDir Path workspace) {
        try (WorkspaceManager wm = new WorkspaceManager(workspace, mockSandboxFilesystem())) {
            String prompt = prompt(wm, true);

            assertNotNull(prompt);
            assertTrue(prompt.contains("call deliver_artifact"), () -> prompt);
            assertFalse(prompt.contains("upload/download tools"), () -> prompt);
        }
    }
}
