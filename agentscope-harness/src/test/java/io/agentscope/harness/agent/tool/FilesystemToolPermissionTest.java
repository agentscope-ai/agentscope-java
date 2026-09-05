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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end permission tests for {@link FilesystemTool} registered through a real {@link
 * Toolkit}, mirroring the production wiring in {@code HarnessAgent}.
 *
 * <p>Permission evaluation runs before execution, so the mocked {@link AbstractFilesystem} is
 * never touched — {@code verifyNoInteractions} asserts exactly that.
 */
class FilesystemToolPermissionTest {

    private AbstractFilesystem filesystem;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        filesystem = mock(AbstractFilesystem.class);
        toolkit = new Toolkit();
        toolkit.registerTool(new FilesystemTool(filesystem));
    }

    private ToolBase registeredTool(String name) {
        AgentTool tool = toolkit.getTool(name);
        return assertInstanceOf(ToolBase.class, tool);
    }

    private PermissionEngine acceptEditsEngine(String workDir) {
        return new PermissionEngine(
                PermissionContextState.builder()
                        .mode(PermissionMode.ACCEPT_EDITS)
                        .addWorkingDirectory(
                                workDir, new AdditionalWorkingDirectory(workDir, "test"))
                        .build());
    }

    @Test
    void writeFileInsideWorkingDirIsNotAutoAllowedBecauseResolverIsOpaque(@TempDir Path workDir) {
        // FilesystemTool's execution landing point depends on the AbstractFilesystem's
        // root/mode/namespace policy, which is not available at permission-check time, so its
        // resolver fails closed and the engine falls back to the default ASK instead of guessing.
        PermissionDecision decision =
                acceptEditsEngine(workDir.toString())
                        .checkPermission(
                                registeredTool("write_file"),
                                Map.of(
                                        "path",
                                        workDir.resolve("a.txt").toString(),
                                        "content",
                                        "hello"))
                        .block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior(), decision.getMessage());
        verifyNoInteractions(filesystem);
    }

    @Test
    void writeFileOutsideWorkingDirAsks(@TempDir Path workDir) {
        PermissionDecision decision =
                acceptEditsEngine(workDir.toString())
                        .checkPermission(
                                registeredTool("write_file"),
                                Map.of("path", "/etc/evil.txt", "content", "hello"))
                        .block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior(), decision.getMessage());
        verifyNoInteractions(filesystem);
    }

    @Test
    void editFileDangerousPathSafetyAsksEvenUnderBypass(@TempDir Path workDir) {
        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder()
                                .mode(PermissionMode.BYPASS)
                                .addWorkingDirectory(
                                        workDir.toString(),
                                        new AdditionalWorkingDirectory(workDir.toString(), "test"))
                                .build());
        PermissionDecision decision =
                engine.checkPermission(
                                registeredTool("edit_file"),
                                Map.of(
                                        "path",
                                        workDir.resolve(".bashrc").toString(),
                                        "old_string",
                                        "x",
                                        "new_string",
                                        "y"))
                        .block();

        assertEquals(PermissionBehavior.ASK, decision.getBehavior());
        assertTrue(
                decision.getDecisionReason().toLowerCase().contains("safety"),
                decision.getDecisionReason());
        verifyNoInteractions(filesystem);
    }
}
